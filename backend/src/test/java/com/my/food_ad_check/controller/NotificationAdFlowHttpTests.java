package com.my.food_ad_check.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.my.food_ad_check.dto.FastApiResponse;
import com.my.food_ad_check.entity.*;
import com.my.food_ad_check.repository.*;
import com.my.food_ad_check.service.AiJudgmentService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class NotificationAdFlowHttpTests {
    @Autowired MockMvc mvc;
    @Autowired com.my.food_ad_check.service.InspectionJobWorker worker;
    @Autowired ProductRepository products;
    @Autowired UserRepository users;
    @Autowired InspectionRecordRepository inspections;
    @Autowired ProductFunctionChangeRepository changes;
    @Autowired ProductChangeNotificationRepository notifications;
    @MockitoBean AiJudgmentService judge;
    private final ObjectMapper json = new ObjectMapper();

    private record Fixture(Product product, User user, InspectionRecord inspection, ProductChangeNotification notification) { }
    private Fixture fixture() throws Exception {
        String id = "adflow-" + UUID.randomUUID();
        Product product = new Product();
        product.setPrdlstReportNo(id);
        product.setPrdlstNm("테스트 제품");
        product.setProductType("건강기능식품");
        product.setPrimaryFnclty("면역력 증진에 도움을 줄 수 있음");
        product.setApprovedFunctions(List.of("면역력 증진"));
        products.saveAndFlush(product);
        User user = users.saveAndFlush(new User(id + "@test.example", "fixture", null));
        String body = json.writeValueAsString(Map.of("request", Map.of("productIds", List.of(id), "adText", "감기 예방."),
                "productInfoSource", "MFDS"));
        var inspection = inspections.saveAndFlush(new InspectionRecord(user.getId(), id, "테스트 제품", null,
                "건강기능식품", "옛 원문", "감기 예방.", "DONE", body, LocalDateTime.now().minusDays(1)));
        var change = changes.saveAndFlush(new ProductFunctionChange(id, "옛 원문", product.getPrimaryFnclty(), LocalDateTime.now()));
        var notice = notifications.saveAndFlush(new ProductChangeNotification(user.getId(), change));
        return new Fixture(product, user, inspection, notice);
    }

    @Test
    void persistsAndSendsProductDetailsAndDetectsChangedCriteria() throws Exception {
        var f = fixture();
        var product = f.product();
        product.setIngredientsSource("홍삼, 비타민C");
        product.setCautionsSource("임산부, 수유부는 상담\n이상사례 발생 시 중단");
        product.setIntake("1일 1회");
        product.setIndividualIngredientsSource("전분");
        product.setEtcIngredientsSource("쌀");
        product.setCapsuleIngredientsSource("젤라틴");
        product.setStandards("규격 원문");
        products.saveAndFlush(product);
        var auth = jwt().jwt(j -> j.subject(f.user().getId().toString()));
        when(judge.requestJudgmentOutcome(any())).thenReturn(new AiJudgmentService.JudgmentOutcome(
                response("원료 안내.", "OK", null), "DONE"));
        String first = mvc.perform(post("/api/inspections").with(auth).contentType("application/json")
                .content(json.writeValueAsString(Map.of("productIds", List.of(product.getPrdlstReportNo()), "adText", "원료 안내."))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(first).path("jobId").asText();
        assertTrue(worker.processOne());
        mvc.perform(get("/api/inspections/" + id).with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.productSnapshot.judgeProduct.ingredients[0]").value("홍삼"))
                .andExpect(jsonPath("$.productSnapshot.judgeProduct.cautions[0]").value("임산부, 수유부는 상담"))
                .andExpect(jsonPath("$.productSnapshot.judgeProduct.intake").value("1일 1회"))
                .andExpect(jsonPath("$.productSnapshot.judgeProduct.otherIngredients[2]").value("젤라틴"))
                .andExpect(jsonPath("$.productSnapshot.judgeProduct.standards").value("규격 원문"));
        var captured = org.mockito.ArgumentCaptor.forClass(com.my.food_ad_check.dto.FastApiRequest.class);
        verify(judge).requestJudgmentOutcome(captured.capture());
        assertEquals(product.getPrimaryFnclty(), captured.getValue().product().functionSource());
        assertEquals(List.of("홍삼", "비타민C"), captured.getValue().product().ingredients());
        product.setCautionsSource("새로운 주의사항");
        products.saveAndFlush(product);
        String next = mvc.perform(post("/api/inspections/" + id + "/recheck").with(auth)
                .contentType("application/json").content("{\"adText\":\"원료 안내.\",\"confirmedCurrentAd\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(worker.processOne());
        mvc.perform(get("/api/inspections/" + json.readTree(next).path("jobId").asText()).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.comparison.sameRecordedCriteria").value(false))
                .andExpect(jsonPath("$.productSnapshot.judgeProduct.cautions[0]").value("새로운 주의사항"));
    }

    private FastApiResponse response(String text, String status, String revision) {
        int end = text.codePointCount(0, text.length());
        return new FastApiResponse(List.of(new FastApiResponse.Sentence("s1", text, 0, end)),
                List.of(new FastApiResponse.Result(List.of("s1"), text, status, null, "reason", List.of(),
                        new FastApiResponse.Span(0, end), revision)), "mock", "2026-11-11", 1L);
    }

    @Test
    void pendingRecheckDisablesAnotherRecheckInNotificationContext() throws Exception {
        var f = fixture();
        var auth = jwt().jwt(j -> j.subject(f.user().getId().toString()));
        String created = mvc.perform(post("/api/inspections/" + f.inspection().getId() + "/recheck").with(auth)
                .contentType("application/json").content("{\"adText\":\"감기 예방.\",\"confirmedCurrentAd\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).path("jobId").asText();
        mvc.perform(get("/api/notifications/" + f.notification().getId() + "/ad-context").with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canRecheck").value(false));
        mvc.perform(post("/api/inspections/" + id + "/reinspections").with(auth)
                .contentType("application/json").content("{\"adText\":\"수정 광고\"}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(judge);
    }

    @Test
    void notificationToCurrentAdToSelectedRecommendationValidation() throws Exception {
        var f = fixture();
        var auth = jwt().jwt(j -> j.subject(f.user().getId().toString()));
        mvc.perform(get("/api/notifications/" + f.notification().getId() + "/ad-context").with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.savedAdText").value("감기 예방."))
                .andExpect(jsonPath("$.canRecheck").value(true));
        String recheckUrl = "/api/inspections/" + f.inspection().getId() + "/recheck";
        mvc.perform(post(recheckUrl).with(auth).contentType("application/json")
                .content("{\"adText\":\"감기 예방.\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(judge);
        when(judge.requestJudgmentOutcome(any())).thenReturn(new AiJudgmentService.JudgmentOutcome(
                response("감기 예방.", "VIOLATION", "원료 안내."), "DONE"),
                new AiJudgmentService.JudgmentOutcome(response("원료 안내.", "OK", null), "DONE"));
        String created = mvc.perform(post(recheckUrl).with(auth).contentType("application/json")
                .content("{\"adText\":\"감기 예방.\",\"confirmedCurrentAd\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String jobId = json.readTree(created).path("jobId").asText();
        mvc.perform(post(recheckUrl).with(auth).contentType("application/json")
                .content("{\"adText\":\"감기 예방.\",\"confirmedCurrentAd\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.jobId").value(jobId));
        verifyNoInteractions(judge);
        assertTrue(worker.processOne());
        mvc.perform(get("/api/inspections/" + jobId + "/recommendations").with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].suggestedText").value("원료 안내."))
                .andExpect(jsonPath("$.items[0].validationStatus").value("UNVERIFIED"));
        String validation = mvc.perform(post("/api/inspections/" + jobId + "/recommendations/finding-0/validate").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String validationId = json.readTree(validation).path("jobId").asText();
        mvc.perform(post("/api/inspections/" + jobId + "/recommendations/finding-0/validate").with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.jobId").value(validationId));
        assertTrue(worker.processOne());
        mvc.perform(get("/api/inspections/" + validationId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationValidation.status").value("PASSED_THIS_CHECK"))
                .andExpect(jsonPath("$.comparison.sentenceSummary.resolvedThisCheck").value(1))
                .andExpect(jsonPath("$.comparison.sentenceChanges[0].mappingMethod").value("SELECTED_RECOMMENDATION"));
        assertEquals("감기 예방.", inspections.findById(f.inspection().getId()).orElseThrow().getAdText());
        f.product().setPrimaryFnclty("기능성 정보가 다시 바뀜");
        products.saveAndFlush(f.product());
        mvc.perform(post("/api/inspections/" + jobId + "/recommendations/finding-0/validate").with(auth))
                .andExpect(status().isConflict());
        verify(judge, times(2)).requestJudgmentOutcome(any());
    }

    @Test
    void missingExtractionBlocksRecheckAndForeignUsersCannotOpenContext() throws Exception {
        var f = fixture();
        f.product().setFunctionSourceReviewRequired(true);
        products.saveAndFlush(f.product());
        var auth = jwt().jwt(j -> j.subject(f.user().getId().toString()));
        mvc.perform(get("/api/notifications/" + f.notification().getId() + "/ad-context").with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canRecheck").value(false));
        mvc.perform(post("/api/inspections/" + f.inspection().getId() + "/recheck").with(auth).contentType("application/json")
                .content("{\"adText\":\"감기 예방.\",\"confirmedCurrentAd\":true}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/notifications/" + f.notification().getId() + "/ad-context")
                .with(jwt().jwt(j -> j.subject("999999")))).andExpect(status().isNotFound());
        verifyNoInteractions(judge);
    }

    @Test
    void userEditRangesLinkManualRevisionAndInvalidHintsDoNotCallJudge() throws Exception {
        var f = fixture();
        var auth = jwt().jwt(j -> j.subject(f.user().getId().toString()));
        when(judge.requestJudgmentOutcome(any())).thenReturn(new AiJudgmentService.JudgmentOutcome(
                response("감기 예방.", "VIOLATION", "원료 안내."), "DONE"),
                new AiJudgmentService.JudgmentOutcome(response("원료 안내.", "OK", null), "DONE"));
        String first = mvc.perform(post("/api/inspections/" + f.inspection().getId() + "/recheck").with(auth)
                .contentType("application/json").content("{\"adText\":\"감기 예방.\",\"confirmedCurrentAd\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String jobId = json.readTree(first).path("jobId").asText();
        assertTrue(worker.processOne());
        String endpoint = "/api/inspections/" + jobId + "/reinspections";
        mvc.perform(post(endpoint).with(auth).contentType("application/json").content("""
                {"adText":"원료 안내.","sentenceEdits":[{"previousSentenceId":"missing","currentStart":0,"currentEnd":6}]}
                """)).andExpect(status().isBadRequest());
        verify(judge, times(1)).requestJudgmentOutcome(any());
        String next = mvc.perform(post(endpoint).with(auth).contentType("application/json").content("""
                {"adText":"원료 안내.","sentenceEdits":[{"previousSentenceId":"s1","currentStart":0,"currentEnd":6}]}
                """)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String nextId = json.readTree(next).path("jobId").asText();
        assertTrue(worker.processOne());
        mvc.perform(get("/api/inspections/" + nextId).with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.comparison.sentenceComparisonAvailable").value(true))
                .andExpect(jsonPath("$.comparison.sentenceSummary.resolvedThisCheck").value(1))
                .andExpect(jsonPath("$.comparison.sentenceChanges[0].mappingMethod").value("USER_EDIT_RANGE"));
    }
}
