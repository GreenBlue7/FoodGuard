package com.my.food_ad_check.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.dto.FastApiRequest;
import com.my.food_ad_check.dto.FastApiResponse;
import com.my.food_ad_check.entity.Product;
import com.my.food_ad_check.entity.User;
import com.my.food_ad_check.repository.*;
import com.my.food_ad_check.service.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class InspectionBatchHttpTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired InspectionRecordRepository records;
    @Autowired InspectionJobWorker worker;
    @Autowired JdbcTemplate db;
    @MockitoBean AiJudgmentService judge;
    private final ObjectMapper json = new ObjectMapper();
    private User user;
    private final List<Product> fixtures = new ArrayList<>();

    @BeforeEach void setup() {
        user = users.saveAndFlush(new User("batch-"+UUID.randomUUID()+"@test.example", "fixture", null));
        for (int i=0;i<3;i++) {
            Product product=new Product();
            product.setPrdlstReportNo("batch-"+UUID.randomUUID());
            product.setPrdlstNm("묶음 검사 제품 "+i);
            product.setProductType("건강기능식품");
            product.setPrimaryFnclty("기능성 "+i);
            product.setApprovedFunctions(List.of("기능성 "+i));
            fixtures.add(products.saveAndFlush(product));
        }
    }
    @AfterEach void cleanup() { users.deleteById(user.getId()); products.deleteAll(fixtures); }
    private Map<String,Object> item(int i) {
        return Map.of("productIds",List.of(fixtures.get(i).getPrdlstReportNo()),"adText","광고 문구 "+i);
    }
    private MvcResult batch(List<?> items) throws Exception {
        return body(json.writeValueAsString(Map.of("items",items)));
    }
    private MvcResult body(String text) throws Exception {
        return mvc.perform(post("/api/inspections/batch").with(jwt().jwt(j->j.subject(user.getId().toString())))
                .contentType("application/json").content(text)).andReturn();
    }
    private JsonNode accepted(List<?> items) throws Exception {
        var result=batch(items); assertEquals(200,result.getResponse().getStatus(),result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString()).path("items");
    }
    private long count(String table) {
        return db.queryForObject("SELECT count(*) FROM "+table+" WHERE user_id=?",Long.class,user.getId());
    }
    private int queued() {
        return db.queryForObject("SELECT count(*) FROM inspection_job_queue q JOIN inspection_records r ON r.id=q.job_id WHERE r.user_id=?",Integer.class,user.getId());
    }
    private JsonNode job(long id) throws Exception {
        return json.readTree(mvc.perform(get("/api/inspections/"+id).with(jwt().jwt(j->j.subject(user.getId().toString()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private AiJudgmentService.JudgmentOutcome outcome(String state) {
        return new AiJudgmentService.JudgmentOutcome("DONE".equals(state)
                ? new FastApiResponse(List.of(),List.of(),"mock","2026-11-17",1L) : null,state);
    }

    @Test void ticketsKeepProductsSeparateAndFailureDoesNotStopTheNextJob() throws Exception {
        var tickets=accepted(List.of(item(0),item(1),item(2)));
        assertEquals(3,tickets.size()); assertEquals(3,queued());
        assertEquals(3,count("inspection_submission_log")); verifyNoInteractions(judge);
        var ids=new HashSet<Long>();
        for(int i=0;i<3;i++) {
            assertEquals(i,tickets.get(i).path("index").asInt());
            long id=tickets.get(i).path("jobId").asLong(); ids.add(id);
            var saved=job(id); assertEquals("PENDING",saved.path("status").asText());
            assertEquals(fixtures.get(i).getPrdlstReportNo(),saved.path("request").path("productIds").get(0).asText());
        }
        assertEquals(3,ids.size());
        when(judge.requestJudgmentOutcome(any())).thenReturn(outcome("FAILED"),outcome("DONE"),outcome("TIMEOUT"));
        assertTrue(worker.processOne());
        assertEquals("FAILED",job(tickets.get(0).path("jobId").asLong()).path("status").asText());
        assertEquals("PENDING",job(tickets.get(1).path("jobId").asLong()).path("status").asText());
        assertTrue(worker.processOne()); assertTrue(worker.processOne()); assertFalse(worker.processOne());
        assertEquals("DONE",job(tickets.get(1).path("jobId").asLong()).path("status").asText());
        assertEquals("TIMEOUT",job(tickets.get(2).path("jobId").asLong()).path("status").asText());
        var capture=ArgumentCaptor.forClass(FastApiRequest.class);
        verify(judge,times(3)).requestJudgmentOutcome(capture.capture());
        for(int i=0;i<3;i++) {
            assertEquals(List.of(fixtures.get(i).getPrdlstNm()),capture.getAllValues().get(i).product().names());
            assertEquals(List.of("기능성 "+i),capture.getAllValues().get(i).product().approvedFunctions());
        }
    }

    @Test void invalidSecondTextRejectsTheWholeListAndExplainsItsIndex() throws Exception {
        var rejected=batch(List.of(item(0),Map.of("adText","😀".repeat(3001))));
        assertEquals(400,rejected.getResponse().getStatus());
        var error=json.readTree(rejected.getResponse().getContentAsString());
        assertEquals("AD_TEXT_TOO_LONG",error.path("code").asText());
        assertTrue(error.path("message").asText().startsWith("2번"));
        assertEquals(0,count("inspection_records")); assertEquals(0,queued());
        assertEquals(0,count("inspection_submission_log")); verifyNoInteractions(judge);
    }

    @Test void unavailableSecondProductRollsBackTheFirstTicketAndItsQuota() throws Exception {
        var product=fixtures.get(1);product.setFunctionSourceReviewRequired(true);fixtures.set(1,products.saveAndFlush(product));
        var rejected=batch(List.of(item(0),item(1)));
        assertEquals(409,rejected.getResponse().getStatus());
        assertEquals("PRODUCT_DATA_NOT_READY",json.readTree(rejected.getResponse().getContentAsString()).path("code").asText());
        assertEquals(0,count("inspection_records")); assertEquals(0,queued());
        assertEquals(0,count("inspection_submission_log")); verifyNoInteractions(judge);
    }

    @Test void activeLimitRollsBackAllNewTicketsWhileLeavingExistingWorkUntouched() throws Exception {
        var existing=accepted(List.of(item(0),item(1)));
        var rejected=batch(List.of(item(2),Map.of("adText","새 문구")));
        assertEquals(429,rejected.getResponse().getStatus()); assertNotNull(rejected.getResponse().getHeader("Retry-After"));
        assertEquals(2,count("inspection_records"));assertEquals(2,queued());assertEquals(2,count("inspection_submission_log"));
        assertEquals("PENDING",job(existing.get(0).path("jobId").asLong()).path("status").asText());
        verifyNoInteractions(judge);
    }

    @Test void rateLimitAlsoRollsBackPartialAdmission() throws Exception {
        when(judge.requestJudgmentOutcome(any())).thenReturn(outcome("DONE"));
        for(int i=0;i<4;i++){accepted(List.of(item(0)));assertTrue(worker.processOne());}
        var rejected=batch(List.of(item(1),item(2)));
        assertEquals(429,rejected.getResponse().getStatus());
        assertEquals("INSPECTION_RATE_LIMIT",json.readTree(rejected.getResponse().getContentAsString()).path("code").asText());
        assertEquals(4,count("inspection_records"));assertEquals(4,count("inspection_submission_log"));assertEquals(0,queued());
        verify(judge,times(4)).requestJudgmentOutcome(any());
    }

    @Test void duplicateItemsAndRepeatedBatchReuseActiveTicketsWithoutExtraQuota() throws Exception {
        var first=accepted(List.of(item(0),item(0),item(1)));
        assertEquals(first.get(0).path("jobId"),first.get(1).path("jobId"));
        var again=accepted(List.of(item(0),item(0),item(1)));
        assertEquals(first,again);assertEquals(2,count("inspection_records"));assertEquals(2,count("inspection_submission_log"));
        assertEquals(2,queued()); verifyNoInteractions(judge);
    }

    @Test void concurrentBatchesDoNotCreateDuplicateJobs() throws Exception {
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<JsonNode> call=()->{start.await();return accepted(List.of(item(0),item(1),item(2)));};
            var a=executor.submit(call);var b=executor.submit(call);start.countDown();
            assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertEquals(3,count("inspection_records"));assertEquals(3,count("inspection_submission_log"));assertEquals(3,queued());
        } finally { start.countDown();executor.shutdownNow(); }
        verifyNoInteractions(judge);
    }

    @Test void emptyOversizedNullAndMalformedListsDoNotCreateTickets() throws Exception {
        for(String value:List.of("null","{}","{\"items\":[]}","{\"items\":[null]}","{\"items\":[{},{},{},{}]}","{")) {
            assertEquals(400,body(value).getResponse().getStatus(),value);
        }
        assertEquals(0,count("inspection_records"));assertEquals(0,count("inspection_submission_log"));verifyNoInteractions(judge);
    }

    @Test void batchRequiresLoginAndTicketsKeepExistingOwnershipChecks() throws Exception {
        mvc.perform(post("/api/inspections/batch").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        var id=accepted(List.of(item(0))).get(0).path("jobId").asText();
        var other=users.saveAndFlush(new User("batch-other-"+UUID.randomUUID()+"@test.example","fixture",null));
        try {
            mvc.perform(get("/api/inspections/"+id).with(jwt().jwt(j->j.subject(other.getId().toString()))))
                    .andExpect(status().isNotFound());
        } finally {users.deleteById(other.getId());}
    }
}
