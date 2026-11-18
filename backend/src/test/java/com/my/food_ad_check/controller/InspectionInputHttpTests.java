package com.my.food_ad_check.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.dto.*;
import com.my.food_ad_check.entity.*;
import com.my.food_ad_check.repository.*;
import com.my.food_ad_check.service.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InspectionInputHttpTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired InspectionRecordRepository records;
    @Autowired InspectionJobWorker worker;
    @Autowired JdbcTemplate db;
    @MockitoBean AiJudgmentService judge;
    private final ObjectMapper json = new ObjectMapper();
    private User user;

    @BeforeEach void account() {
        user=users.saveAndFlush(new User("input-"+UUID.randomUUID()+"@test.example","fixture",null));
    }
    private JsonNode submit(String text, int expected) throws Exception {
        var body=json.writeValueAsString(Map.of("productIds",List.of(),"productTypeAnswer","GENERAL","adText",text));
        var result=mvc.perform(post("/api/inspections").with(jwt().jwt(j -> j.subject(user.getId().toString())))
                .contentType("application/json").content(body)).andReturn();
        assertEquals(expected,result.getResponse().getStatus());
        return json.readTree(result.getResponse().getContentAsString());
    }
    private JsonNode job(long id) throws Exception {
        return json.readTree(mvc.perform(get("/api/inspections/"+id).with(jwt().jwt(j -> j.subject(user.getId().toString()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private void noNewAdmission() {
        assertEquals(0,db.queryForObject("SELECT count(*) FROM inspection_submission_log WHERE user_id=?",Integer.class,user.getId()));
        verifyNoInteractions(judge);
    }

    @ParameterizedTest @ValueSource(strings={"가","😀"})
    void exactly3000CodePointsAreAcceptedWithoutRequiringDisclaimer(String character) throws Exception {
        var accepted=submit(character.repeat(3000),200);
        var result=job(accepted.path("jobId").asLong());
        assertEquals("PENDING",result.path("status").asText());
        assertEquals("INPUT_VALIDATION",result.path("layer1").path("kind").asText());
        assertEquals("PASSED",result.path("layer1").path("status").asText());
        assertEquals(3000,result.path("layer1").path("characterCount").asInt());
        assertEquals(3000,result.path("layer1").path("maxCharacters").asInt());
        assertFalse(result.has("provisionalStep1"));
        verifyNoInteractions(judge);
    }

    @ParameterizedTest @ValueSource(strings={"가","😀"})
    void rejects3001CodePointsBeforeCreatingAJobOrChargingQuota(String character) throws Exception {
        var rejected=submit(character.repeat(3001),400);
        assertEquals("AD_TEXT_TOO_LONG",rejected.path("code").asText());
        assertEquals("REJECTED",rejected.path("layer1").path("status").asText());
        assertEquals(3001,rejected.path("layer1").path("characterCount").asInt());
        assertFalse(rejected.has("jobId"));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM inspection_records WHERE user_id=?",Integer.class,user.getId()));
        noNewAdmission();
    }

    @Test void countsWhitespaceAndNewlinesWithoutTruncatingTheText() throws Exception {
        assertEquals("AD_TEXT_TOO_LONG",submit(" "+"가".repeat(2999)+"\n",400).path("code").asText());
        noNewAdmission();
    }

    @ParameterizedTest @ValueSource(strings={""," \n\t","\u00a0\u3000"})
    void rejectsEmptyAndWhitespaceOnlyText(String text) throws Exception {
        assertEquals("EMPTY_AD_TEXT",submit(text,400).path("code").asText());
        noNewAdmission();
    }

    @Test void malformedRequestsUseSafeStructuredLayer1Errors() throws Exception {
        for(String body:List.of("{", "{\"adText\":{}}", "null")) {
            mvc.perform(post("/api/inspections").with(jwt().jwt(j -> j.subject(user.getId().toString())))
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                    .andExpect(jsonPath("$.layer1.kind").value("INPUT_VALIDATION"));
        }
        noNewAdmission();
    }

    @ParameterizedTest @ValueSource(strings={"reinspections","recheck"})
    void reinspectionRoutesShareThe3000CharacterLimit(String route) throws Exception {
        var previous=records.saveAndFlush(new InspectionRecord(user.getId(),null,null,null,null,null,"이전 광고","DONE",
                "{\"request\":{\"productIds\":[],\"productTypeAnswer\":\"GENERAL\",\"adText\":\"이전 광고\"}}",LocalDateTime.now()));
        String body=json.writeValueAsString(Map.of("adText","가".repeat(3001),"confirmedCurrentAd",true));
        mvc.perform(post("/api/inspections/"+previous.getId()+"/"+route)
                .with(jwt().jwt(j -> j.subject(user.getId().toString()))).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AD_TEXT_TOO_LONG"));
        noNewAdmission();
    }

    @Test void unknownProductTypeRemainsInspectableWithAnExplicitNotice() throws Exception {
        String body="{\"productIds\":[],\"productTypeAnswer\":\"UNKNOWN\",\"adText\":\"광고 문구\"}";
        var accepted=json.readTree(mvc.perform(post("/api/inspections").with(jwt().jwt(j -> j.subject(user.getId().toString())))
                .contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var result=job(accepted.path("jobId").asLong());
        assertEquals("UNVERIFIED",result.path("productInfoSource").asText());
        assertEquals("PASSED",result.path("layer1").path("status").asText());
        assertFalse(result.path("layer1").path("notices").isEmpty());
    }

    @Test void extractionFailureIsADataReadinessRejection() throws Exception {
        Product product=new Product();
        product.setPrdlstReportNo("input-"+UUID.randomUUID());product.setPrdlstNm("테스트 제품");
        product.setProductType("건강기능식품");product.setFunctionSourceReviewRequired(true);products.saveAndFlush(product);
        String body=json.writeValueAsString(Map.of("productIds",List.of(product.getPrdlstReportNo()),"adText","광고 문구"));
        mvc.perform(post("/api/inspections").with(jwt().jwt(j -> j.subject(user.getId().toString())))
                .contentType("application/json").content(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_DATA_NOT_READY"))
                .andExpect(jsonPath("$.layer1.status").value("REJECTED"));
        noNewAdmission();
    }

    @Test void advertisingMeaningIsPassedToJudgeInsteadOfHardCodedInLayer1() throws Exception {
        String ad="이 제품으로 질병이 완치됩니다.";
        long id=submit(ad,200).path("jobId").asLong();
        when(judge.requestJudgmentOutcome(any())).thenReturn(new AiJudgmentService.JudgmentOutcome(null,"FAILED"));
        assertTrue(worker.processOne());
        var argument=org.mockito.ArgumentCaptor.forClass(FastApiRequest.class);
        verify(judge).requestJudgmentOutcome(argument.capture());
        assertEquals(ad,argument.getValue().adText());
        assertEquals("PASSED",job(id).path("layer1").path("status").asText());
    }

    @Test void legacyOversizedQueuedJobIsRejectedWithoutCallingJudge() throws Exception {
        String text="가".repeat(3001);
        var record=records.saveAndFlush(new InspectionRecord(user.getId(),null,null,null,null,null,text,"PENDING",
                json.writeValueAsString(Map.of("request",Map.of("adText",text),"productInfoSource","UNVERIFIED")),LocalDateTime.now()));
        var request=new FastApiRequest("old","C",new FastApiRequest.Product("GENERAL_FOOD",List.of(),List.of()),text);
        db.update("INSERT INTO inspection_job_queue(job_id,judge_request_json) VALUES(?,?)",record.getId(),json.writeValueAsString(request));
        assertTrue(worker.processOne());
        var result=job(record.getId());
        assertEquals("FAILED",result.path("status").asText());
        assertEquals("AD_TEXT_TOO_LONG",result.path("failureCode").asText());
        assertEquals("REJECTED",result.path("layer1").path("status").asText());
        verifyNoInteractions(judge);
    }

    @Test void newPreflightReusesOldActiveFingerprints() throws Exception {
        long id=submit("광고",200).path("jobId").asLong();
        db.update("""
                UPDATE inspection_records SET request_fingerprint=?,result_json=(result_json::jsonb ||
                    '{"provisionalStep1":{"status":"FAIL","missingItems":["old notice"]}}'::jsonb)::text WHERE id=?
                ""","0".repeat(64),id);
        assertEquals(id,submit("광고",200).path("jobId").asLong());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM inspection_submission_log WHERE user_id=?",Integer.class,user.getId()));
        verifyNoInteractions(judge);
    }

    @ParameterizedTest @ValueSource(ints={3000,3001})
    void selectedRecommendationIsCheckedAfterApplyingTheReplacement(int size) throws Exception {
        String ad="기존 문구.";
        var product=new Product();product.setPrdlstReportNo("revision-"+UUID.randomUUID());
        product.setPrdlstNm("테스트 제품");product.setProductType("건강기능식품");product.setPrimaryFnclty("원문");products.saveAndFlush(product);
        var response=new FastApiResponse(List.of(new FastApiResponse.Sentence("s1",ad,0,ad.length())),
                List.of(new FastApiResponse.Result(List.of("s1"),ad,"VIOLATION",null,"테스트",List.of(),
                        new FastApiResponse.Span(0,ad.length()),"가".repeat(size))),"mock","2026-11-17",1L);
        String result=json.writeValueAsString(Map.of("request",new InspectionJobRequest(List.of(product.getPrdlstReportNo()),null,ad),
                "productSnapshot",Map.of("version",product.getVersion(),"primaryFnclty","원문"),"judgeResponse",response));
        var previous=records.saveAndFlush(new InspectionRecord(user.getId(),product.getPrdlstReportNo(),null,null,null,null,
                ad,"DONE",result,LocalDateTime.now()));
        var request=mvc.perform(post("/api/inspections/"+previous.getId()+"/recommendations/finding-0/validate")
                .with(jwt().jwt(j -> j.subject(user.getId().toString())))).andReturn();
        assertEquals(size==3000?200:400,request.getResponse().getStatus());
        if(size>3000) {
            assertEquals("AD_TEXT_TOO_LONG",json.readTree(request.getResponse().getContentAsString()).path("code").asText());
            noNewAdmission();
        }
        verifyNoInteractions(judge);
    }
}
