package com.my.food_ad_check.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.dto.FastApiResponse;
import com.my.food_ad_check.entity.Product;
import com.my.food_ad_check.entity.User;
import com.my.food_ad_check.repository.*;
import com.my.food_ad_check.service.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP + committed PostgreSQL queue, with the paid judge replaced. */
@SpringBootTest
@AutoConfigureMockMvc
class AsyncInspectionHttpTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired InspectionRecordRepository records;
    @Autowired InspectionJobQueue queue;
    @Autowired InspectionJobWorker worker;
    @Autowired JdbcTemplate db;
    @MockitoBean AiJudgmentService judge;
    private final ObjectMapper json = new ObjectMapper();
    private Long userId;
    private String productId;

    @BeforeEach void account() {
        userId = users.saveAndFlush(new User("async-" + UUID.randomUUID() + "@test.example", "fixture", null)).getId();
    }

    @AfterEach void cleanup() {
        users.deleteById(userId);
        if (productId != null) products.deleteById(productId);
    }

    private long submit(List<String> ids) throws Exception {
        String body = mvc.perform(post("/api/inspections").with(jwt().jwt(j -> j.subject(userId.toString())))
                .contentType("application/json").content(json.writeValueAsString(Map.of(
                        "productIds", ids, "adText", "광고 문구.", "productTypeAnswer", "GENERAL"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("jobId").asLong();
    }

    private void state(long id, String status) throws Exception {
        mvc.perform(get("/api/inspections/" + id).with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(status));
    }

    private FastApiResponse answer() {
        return new FastApiResponse(List.of(new FastApiResponse.Sentence("s1", "광고 문구.", 0, 6)),
                List.of(new FastApiResponse.Result(List.of("s1"), "광고 문구.", "OK", null, "테스트 응답",
                        List.of(), new FastApiResponse.Span(0, 6), null)), "mock", "2026-11-14", 1L);
    }

    @Test void returnsPendingBeforeJudgeAndExposesRunningThenDoneWithoutDuplicateCalls() throws Exception {
        long id = submit(List.of());
        verifyNoInteractions(judge);
        state(id, "PENDING");
        mvc.perform(get("/api/inspections/" + id).with(jwt().jwt(j -> j.subject("999999999"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/inspections/" + id + "/report").with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isConflict());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(judge.requestJudgmentOutcome(any())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new AiJudgmentService.JudgmentOutcome(answer(), "DONE");
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> task = executor.submit(worker::processOne);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            state(id, "RUNNING");
            assertEquals(id, submit(List.of()), "Same input must reuse a RUNNING job");
            assertFalse(new InspectionJobWorker(queue, records, products, judge).processOne());
            release.countDown();
            assertTrue(task.get(5, TimeUnit.SECONDS));
            state(id, "DONE");
            assertFalse(worker.processOne());
            verify(judge, times(1)).requestJudgmentOutcome(any());
            assertEquals(0, db.queryForObject("SELECT count(*) FROM inspection_job_queue WHERE job_id=?", Integer.class, id));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void newWorkerResumesDurablePendingInput() throws Exception {
        long id = submit(List.of());
        when(judge.requestJudgmentOutcome(any())).thenReturn(new AiJudgmentService.JudgmentOutcome(answer(), "DONE"));
        assertTrue(new InspectionJobWorker(queue, records, products, judge).processOne());
        state(id, "DONE");
        assertEquals("광고 문구.", records.findById(id).orElseThrow().getAdText());
    }

    @Test void expiredRunningWorkFailsWithoutReplayingAndRejectsLateCompletion() throws Exception {
        long id = submit(List.of());
        db.update("""
                UPDATE inspection_records SET result_json=(result_json::jsonb ||
                    '{"recommendationValidation":{"status":"PENDING"}}'::jsonb)::text WHERE id=?
                """, id);
        var oldClaim = queue.claim().orElseThrow();
        db.update("UPDATE inspection_job_queue SET lease_until=clock_timestamp()-interval '1 second' WHERE job_id=?", id);
        assertFalse(new InspectionJobWorker(queue, records, products, judge).processOne());
        state(id, "FAILED");
        assertTrue(records.findById(id).orElseThrow().getResultJson().contains("WORKER_INTERRUPTED"));
        var result = json.readTree(records.findById(id).orElseThrow().getResultJson());
        assertEquals("REVIEW_REQUIRED", result.path("recommendationValidation").path("status").asText());
        assertTrue(result.hasNonNull("completedAt"));
        assertFalse(queue.finish(oldClaim, "DONE", Map.of()));
        verifyNoInteractions(judge);
    }

    @ParameterizedTest @ValueSource(strings={"FAILED", "TIMEOUT"})
    void preservesJudgeFailureStatusAndDoesNotAutomaticallyRetry(String status) throws Exception {
        long id = submit(List.of());
        when(judge.requestJudgmentOutcome(any())).thenReturn(new AiJudgmentService.JudgmentOutcome(null, status));
        assertTrue(worker.processOne());
        state(id, status);
        assertFalse(worker.processOne());
        verify(judge, times(1)).requestJudgmentOutcome(any());
    }

    @Test void unexpectedWorkerExceptionBecomesFailedWithoutLeakingDetails() throws Exception {
        long id = submit(List.of());
        when(judge.requestJudgmentOutcome(any())).thenThrow(new IllegalStateException("private provider detail"));
        assertTrue(worker.processOne());
        state(id, "FAILED");
        assertFalse(records.findById(id).orElseThrow().getResultJson().contains("private provider detail"));
    }

    private Product product() {
        Product p = new Product();
        productId = "async-" + UUID.randomUUID();
        p.setPrdlstReportNo(productId);
        p.setPrdlstNm("비동기 검증 제품");
        p.setProductType("일반식품");
        p.setPrimaryFnclty("원문");
        return products.saveAndFlush(p);
    }

    @Test void productChangedWhileWaitingBlocksPaidCall() throws Exception {
        Product p = product();
        long id = submit(List.of(productId));
        p.setPrimaryFnclty("변경 원문");
        products.saveAndFlush(p);
        assertTrue(worker.processOne());
        state(id, "FAILED");
        assertTrue(records.findById(id).orElseThrow().getResultJson().contains("PRODUCT_CHANGED"));
        verifyNoInteractions(judge);
    }

    @Test void productChangedDuringJudgeDoesNotSaveOutdatedVerdict() throws Exception {
        Product p = product();
        long id = submit(List.of(productId));
        when(judge.requestJudgmentOutcome(any())).thenAnswer(call -> {
            p.setPrimaryFnclty("검사 도중 변경");
            products.saveAndFlush(p);
            return new AiJudgmentService.JudgmentOutcome(answer(), "DONE");
        });
        assertTrue(worker.processOne());
        state(id, "FAILED");
        var result = json.readTree(records.findById(id).orElseThrow().getResultJson());
        assertEquals("PRODUCT_CHANGED", result.path("failureCode").asText());
        assertTrue(result.path("layer2").isNull());
    }

    @Test void simultaneousIdenticalSubmissionsProduceOneRecordAndOnePaidCall() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            var requests = new java.util.ArrayList<Future<Long>>();
            for (int i = 0; i < 8; i++) requests.add(executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return submit(List.of());
            }));
            start.countDown();
            var ids = new java.util.HashSet<Long>();
            for (var request : requests) ids.add(request.get(10, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
            assertEquals(1, db.queryForObject("SELECT count(*) FROM inspection_records WHERE user_id=?", Integer.class, userId));
            verifyNoInteractions(judge);
            when(judge.requestJudgmentOutcome(any())).thenReturn(new AiJudgmentService.JudgmentOutcome(answer(), "DONE"));
            assertTrue(worker.processOne());
            assertFalse(worker.processOne());
            verify(judge, times(1)).requestJudgmentOutcome(any());
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(strings={"DONE", "FAILED", "TIMEOUT"})
    void terminalJobDoesNotPreventAnExplicitNewInspection(String status) throws Exception {
        long first = submit(List.of());
        when(judge.requestJudgmentOutcome(any())).thenReturn(new AiJudgmentService.JudgmentOutcome(
                "DONE".equals(status) ? answer() : null, status));
        assertTrue(worker.processOne());
        state(first, status);
        assertNotEquals(first, submit(List.of()));
    }

    @Test void expiredWorkAllowsNewSubmissionAfterRecovery() throws Exception {
        long first = submit(List.of());
        queue.claim().orElseThrow();
        db.update("UPDATE inspection_job_queue SET lease_until=clock_timestamp()-interval '1 second' WHERE job_id=?", first);
        assertEquals(1, queue.recoverExpired());
        assertNotEquals(first, submit(List.of()));
        verifyNoInteractions(judge);
    }

    @Test void sameTextWithChangedProductVersionIsANewInspection() throws Exception {
        Product p = product();
        long first = submit(List.of(productId));
        p.setPrimaryFnclty("새 원문");
        products.saveAndFlush(p);
        assertNotEquals(first, submit(List.of(productId)));
        verifyNoInteractions(judge);
    }

    @Test void differentAccountsNeverShareJobIds() throws Exception {
        long first = submit(List.of());
        User other = users.saveAndFlush(new User("other-async-" + UUID.randomUUID() + "@test.example", "fixture", null));
        try {
            String body = mvc.perform(post("/api/inspections").with(jwt().jwt(j -> j.subject(other.getId().toString())))
                    .contentType("application/json").content("""
                            {"productIds":[],"productTypeAnswer":"GENERAL","adText":"광고 문구."}
                            """))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertNotEquals(first, json.readTree(body).path("jobId").asLong());
        } finally { users.deleteById(other.getId()); }
    }

    @Test void adoptsAlreadyQueuedWorkWithoutFingerprintingHistoricResults() throws Exception {
        long first = submit(List.of());
        db.update("UPDATE inspection_records SET request_fingerprint=NULL WHERE id=?", first);
        assertEquals(first, submit(List.of()));
        assertNotNull(db.queryForObject("SELECT request_fingerprint FROM inspection_records WHERE id=?", String.class, first));
        verifyNoInteractions(judge);
    }

    @Test void differentAdTextIsNotMerged() throws Exception {
        long first = submit(List.of());
        String body = mvc.perform(post("/api/inspections").with(jwt().jwt(j -> j.subject(userId.toString())))
                .contentType("application/json").content("""
                        {"productIds":[],"productTypeAnswer":"GENERAL","adText":"다른 광고 문구."}
                        """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertNotEquals(first, json.readTree(body).path("jobId").asLong());
    }
}
