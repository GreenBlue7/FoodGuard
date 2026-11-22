package com.my.food_ad_check.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.dto.FastApiResponse;
import com.my.food_ad_check.entity.InspectionRecord;
import com.my.food_ad_check.entity.User;
import com.my.food_ad_check.repository.*;
import com.my.food_ad_check.service.*;
import java.time.LocalDateTime;
import java.util.*;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class InspectionAdmissionHttpTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired InspectionRecordRepository records;
    @Autowired InspectionJobWorker worker;
    @Autowired InspectionJobQueue queue;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean AiJudgmentService judge;
    private final ObjectMapper json = new ObjectMapper();
    private Long userId;

    @BeforeEach void account() {
        userId = users.saveAndFlush(new User("limit-" + UUID.randomUUID() + "@test.example", "fixture", null)).getId();
    }
    @AfterEach void cleanup() { users.deleteById(userId); }

    private MvcResult request(Long user, String text) throws Exception {
        return mvc.perform(post("/api/inspections").with(jwt().jwt(j -> j.subject(user.toString())))
                .contentType("application/json").content(json.writeValueAsString(Map.of(
                        "productIds", List.of(), "productTypeAnswer", "GENERAL", "adText", text)))).andReturn();
    }
    private long accepted(String text) throws Exception {
        var result = request(userId, text);
        assertEquals(200, result.getResponse().getStatus());
        return json.readTree(result.getResponse().getContentAsString()).path("jobId").asLong();
    }
    private void denied(MvcResult result, String code) throws Exception {
        assertEquals(429, result.getResponse().getStatus());
        var body = json.readTree(result.getResponse().getContentAsString());
        assertEquals(code, body.path("code").asText());
        assertFalse(body.path("message").asText().isBlank());
        assertTrue(body.path("retryAfterSeconds").asInt() > 0);
        assertEquals(body.path("retryAfterSeconds").asText(), result.getResponse().getHeader("Retry-After"));
        assertFalse(body.has("jobId"));
    }
    private long count(String table) {
        // Only internal constants are passed as table names.
        return db.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id=?", Long.class, userId);
    }
    private void outcome(String status) {
        var answer = "DONE".equals(status) ? new FastApiResponse(List.of(), List.of(), "mock", "2026-11-16", 1L) : null;
        when(judge.requestJudgmentOutcome(any())).thenReturn(new AiJudgmentService.JudgmentOutcome(answer, status));
    }

    @Test void activeLimitRejectsNewWorkButAllowsDuplicateAndReadOnlyLookup() throws Exception {
        long first = accepted("광고 1");
        accepted("광고 2"); accepted("광고 3");
        denied(request(userId, "광고 4"), "INSPECTION_ACTIVE_LIMIT");
        assertEquals(first, accepted("광고 1"));
        mvc.perform(get("/api/inspections/" + first).with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/inspections").with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk());
        assertEquals(3, count("inspection_records"));
        assertEquals(3, count("inspection_submission_log"));
        verifyNoInteractions(judge);
    }

    @ParameterizedTest @ValueSource(strings={"DONE","FAILED","TIMEOUT"})
    void terminalJobsReleaseActiveSlots(String status) throws Exception {
        accepted("광고 1"); accepted("광고 2"); accepted("광고 3");
        outcome(status);
        assertTrue(worker.processOne());
        accepted("광고 4");
        assertEquals(4, count("inspection_records"));
        assertEquals(4, count("inspection_submission_log"));
        verify(judge, times(1)).requestJudgmentOutcome(any());
    }

    @ParameterizedTest @ValueSource(strings={"DONE","FAILED","TIMEOUT"})
    void rollingWindowCountsAcceptedFailuresAndRejectsWithoutCreatingAJob(String status) throws Exception {
        outcome(status);
        for (int i=0; i<5; i++) { accepted("다시 검사할 광고"); assertTrue(worker.processOne()); }
        var rejected = request(userId, "다시 검사할 광고");
        denied(rejected, "INSPECTION_RATE_LIMIT");
        assertTrue(json.readTree(rejected.getResponse().getContentAsString()).path("retryAfterSeconds").asInt() <= 60);
        assertEquals(5, count("inspection_records"));
        assertEquals(5, count("inspection_submission_log"));
        verify(judge, times(5)).requestJudgmentOutcome(any());
    }

    @Test void expiredWindowAllowsANewJobWithoutRestartingTheServer() throws Exception {
        outcome("DONE");
        for(int i=0;i<5;i++) { accepted("광고"); assertTrue(worker.processOne()); }
        db.update("UPDATE inspection_submission_log SET accepted_at=clock_timestamp()-interval '61 seconds' WHERE user_id=?", userId);
        accepted("새 광고");
        assertEquals(1, count("inspection_submission_log")); // expired quota events were pruned
    }

    @Test void duplicateAtRateLimitStillReturnsExistingJobAndConsumesNoQuota() throws Exception {
        outcome("DONE");
        for(int i=0;i<4;i++) { accepted("완료된 광고"); assertTrue(worker.processOne()); }
        long existing = accepted("아직 진행 중인 광고");
        for(int i=0;i<3;i++) assertEquals(existing, accepted("아직 진행 중인 광고"));
        denied(request(userId,"다른 광고"),"INSPECTION_RATE_LIMIT");
        assertEquals(5,count("inspection_submission_log"));
    }

    @Test void simultaneousDifferentRequestsCannotOverrunActiveLimit() throws Exception {
        var results = parallel(8, "서로 다른 동시 광고 ");
        assertEquals(3, results.stream().filter(r -> r.getResponse().getStatus()==200).count());
        for(var result : results) if(result.getResponse().getStatus()!=200) denied(result,"INSPECTION_ACTIVE_LIMIT");
        assertEquals(3,count("inspection_records"));
        assertEquals(3,count("inspection_submission_log"));
        verifyNoInteractions(judge);
    }

    @Test void simultaneousRequestsCannotTakeTheLastRateSlotTwice() throws Exception {
        outcome("DONE");
        for(int i=0;i<4;i++) { accepted("완료 광고"); assertTrue(worker.processOne()); }
        var results = parallel(2,"마지막 접수 ");
        assertEquals(1,results.stream().filter(r -> r.getResponse().getStatus()==200).count());
        for(var result:results) if(result.getResponse().getStatus()!=200) denied(result,"INSPECTION_RATE_LIMIT");
        assertEquals(5,count("inspection_submission_log"));
    }

    private List<MvcResult> parallel(int count,String prefix) throws Exception {
        var executor=Executors.newFixedThreadPool(count);
        var start=new CountDownLatch(1);
        try {
            List<Future<MvcResult>> tasks=new ArrayList<>();
            for(int i=0;i<count;i++) { final String text=prefix+i; tasks.add(executor.submit(() -> {
                assertTrue(start.await(5,TimeUnit.SECONDS)); return request(userId,text);
            })); }
            start.countDown();
            List<MvcResult> results=new ArrayList<>();
            for(var task:tasks) results.add(task.get(10,TimeUnit.SECONDS));
            return results;
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test void anotherUsersQuotaIsIndependent() throws Exception {
        accepted("광고 1"); accepted("광고 2"); accepted("광고 3");
        var other=users.saveAndFlush(new User("other-limit-"+UUID.randomUUID()+"@test.example","fixture",null));
        try { assertEquals(200,request(other.getId(),"광고 1").getResponse().getStatus()); }
        finally { users.deleteById(other.getId()); }
    }

    @Test void reinspectionAndCurrentAdRecheckUseTheSameAdmissionLimit() throws Exception {
        var previous=records.saveAndFlush(new InspectionRecord(userId,null,null,null,null,null,"이전 광고","DONE",
                "{\"request\":{\"productIds\":[],\"productTypeAnswer\":\"GENERAL\",\"adText\":\"이전 광고\"}}",LocalDateTime.now()));
        accepted("광고 1"); accepted("광고 2"); accepted("광고 3");
        var auth=jwt().jwt(j -> j.subject(userId.toString()));
        var edited=mvc.perform(post("/api/inspections/"+previous.getId()+"/reinspections").with(auth)
                .contentType("application/json").content("{\"adText\":\"수정 광고\"}")).andReturn();
        denied(edited,"INSPECTION_ACTIVE_LIMIT");
        var recheck=mvc.perform(post("/api/inspections/"+previous.getId()+"/recheck").with(auth)
                .contentType("application/json").content("{\"adText\":\"이전 광고\",\"confirmedCurrentAd\":true}")).andReturn();
        denied(recheck,"INSPECTION_ACTIVE_LIMIT");
        assertEquals(3,count("inspection_submission_log"));
        verifyNoInteractions(judge);
    }

    @Test void rollingBackAdmissionRollsBackItsRecordQueueAndCounter() throws Exception {
        var tx=new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            try { accepted("롤백할 광고"); }
            catch(Exception e) { throw new IllegalStateException(e); }
            assertEquals(1,count("inspection_submission_log"));
            status.setRollbackOnly();
        });
        assertEquals(0,count("inspection_records"));
        assertEquals(0,count("inspection_submission_log"));
    }
}
