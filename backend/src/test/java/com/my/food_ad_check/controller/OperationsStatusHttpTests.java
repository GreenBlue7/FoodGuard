package com.my.food_ad_check.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.entity.*;
import com.my.food_ad_check.repository.*;
import com.my.food_ad_check.service.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OperationsStatusHttpTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired InspectionRecordRepository records;
    @Autowired ProductFunctionChangeRepository changes;
    @Autowired ProductChangeNotificationRepository notices;
    @Autowired ProductSyncStateRepository states;
    @Autowired ProductSyncRunTracker tracker;
    @MockitoBean AiJudgmentService judge;
    @MockitoBean JavaMailSender mail;
    private final ObjectMapper json = new ObjectMapper();
    private static final String URL="/api/operations/status";

    private JsonNode readStatus() throws Exception {
        String body=mvc.perform(get(URL).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test void onlyAdministratorsCanReadGlobalOperationalCounts() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(get(URL).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
        assertEquals("UP",readStatus().path("database").path("status").asText());
        verifyNoInteractions(judge,mail);
    }

    @Test void countsExpiredJobsAndMailFailuresWithoutRecoveringOrSending() throws Exception {
        JsonNode before=readStatus();
        String productId="ops-"+UUID.randomUUID();
        var product=new Product();
        product.setPrdlstReportNo(productId);product.setPrdlstNm("private-product-sentinel");
        product.setProductType("일반식품");products.saveAndFlush(product);
        var user=users.saveAndFlush(new User("private-user-"+UUID.randomUUID()+"@test.example","private-password-sentinel",null));
        var pending=records.saveAndFlush(new InspectionRecord(user.getId(),productId,null,null,null,null,
                "private-ad-sentinel","PENDING","{}",LocalDateTime.now()));
        var running=records.saveAndFlush(new InspectionRecord(user.getId(),productId,null,null,null,null,
                "private-ad-sentinel","RUNNING","{}",LocalDateTime.now()));
        db.update("INSERT INTO inspection_job_queue(job_id,judge_request_json) VALUES(?,'{}')",pending.getId());
        db.update("""
                INSERT INTO inspection_job_queue(job_id,judge_request_json,claim_token,lease_until)
                VALUES(?,'{}',?,clock_timestamp()-interval '1 second')
                """,running.getId(),UUID.randomUUID());
        for(String state:List.of("PENDING","PROCESSING","SENT","FAILED","SKIPPED")) {
            var change=changes.saveAndFlush(new ProductFunctionChange(productId,"private-old-source","private-new-source",LocalDateTime.now()));
            var notice=notices.saveAndFlush(new ProductChangeNotification(user.getId(),change));
            db.update("""
                    INSERT INTO notification_email_outbox(notification_id,user_id,state,attempts,lease_until,sent_at,last_error)
                    VALUES(?,?,?,1,localtimestamp-interval '1 second',localtimestamp,'private-mail-error-sentinel')
                    """,notice.getId(),user.getId(),state);
        }
        var after=readStatus();
        assertEquals("ATTENTION",after.path("status").asText());
        assertEquals(before.path("inspections").path("pending").asLong()+1,after.path("inspections").path("pending").asLong());
        assertEquals(before.path("inspections").path("running").asLong()+1,after.path("inspections").path("running").asLong());
        assertEquals(before.path("inspections").path("expiredRunning").asLong()+1,after.path("inspections").path("expiredRunning").asLong());
        for(String field:List.of("pending","retryPending","processing","sent","failed","skipped","expiredProcessing")) {
            assertEquals(before.path("email").path(field).asLong()+1,after.path("email").path(field).asLong(),field);
        }
        assertEquals("RUNNING",db.queryForObject("SELECT status FROM inspection_records WHERE id=?",String.class,running.getId()));
        assertEquals(5,db.queryForObject("SELECT count(*) FROM notification_email_outbox WHERE user_id=?",Integer.class,user.getId()));
        assertFalse(after.toString().contains("private-"));
        assertEquals("NOT_CHECKED",after.path("externalChecks").path("judge").asText());
        assertEquals("NOT_CHECKED",after.path("externalChecks").path("smtp").asText());
        verifyNoInteractions(judge,mail);
    }

    @Test void missingCollectionHistoryRemainsUnknownInsteadOfInventingSuccess() throws Exception {
        db.update("DELETE FROM product_sync_runs WHERE service_id='I0030'");
        db.update("DELETE FROM product_sync_state WHERE service_id='I0030'");
        var sync=readStatus().path("productSync");
        for(String field:List.of("coveredThrough","lastFullSyncOn","lastRecordedSuccessAt","latestRun")) assertTrue(sync.path(field).isNull(),field);
    }

    @Test void collectionSuccessAndFailuresArePersistedWithoutProviderMessages() throws Exception {
        var api=mock(ProductApiService.class);
        var refresh=new ProductRefreshService(api,states,tracker);
        when(api.importSince(any())).thenReturn(new ProductApiService.ImportOutcome(true,"private-source-result"));
        refresh.refresh();
        var saved=states.findById("I0030").orElseThrow();
        var first=readStatus().path("productSync");
        assertEquals("SUCCEEDED",first.path("latestRun").path("status").asText());
        assertEquals(saved.getCoveredThrough().toString(),first.path("coveredThrough").asText());
        assertFalse(first.path("lastRecordedSuccessAt").isNull());
        when(api.importSince(any())).thenReturn(new ProductApiService.ImportOutcome(false,"private-provider-key"));
        refresh.refresh();
        var second=readStatus();
        assertEquals("ATTENTION",second.path("status").asText());
        assertEquals("IMPORT_NOT_COMPLETED",second.path("productSync").path("latestRun").path("errorCode").asText());
        assertEquals(first.path("lastRecordedSuccessAt"),second.path("productSync").path("lastRecordedSuccessAt"));
        when(api.importSince(any())).thenThrow(new IllegalStateException("private-provider-key"));
        assertThrows(IllegalStateException.class,refresh::refresh);
        var third=readStatus();
        assertEquals("IMPORT_EXCEPTION",third.path("productSync").path("latestRun").path("errorCode").asText());
        assertFalse(third.toString().contains("private-"));
        verifyNoInteractions(judge,mail);
    }

    @Test void unavailableSchemaReturns503AndNullMetrics() throws Exception {
        db.update("ALTER TABLE product_sync_runs RENAME TO ops_temporarily_unavailable");
        String body=mvc.perform(get(URL).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        var response=json.readTree(body);
        assertEquals("UP",response.path("database").path("status").asText());
        assertEquals("UNAVAILABLE",response.path("status").asText());
        assertEquals("STATUS_QUERY_FAILED",response.path("errorCode").asText());
        assertTrue(response.path("inspections").isNull());
        assertFalse(body.contains("SELECT"));
        assertFalse(body.contains("ops_temporarily_unavailable"));
    }
}
