package com.my.food_ad_check.controller;

import com.my.food_ad_check.entity.InspectionRecord;
import com.my.food_ad_check.entity.User;
import com.my.food_ad_check.repository.InspectionRecordRepository;
import com.my.food_ad_check.repository.UserRepository;
import com.my.food_ad_check.service.AiJudgmentService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InspectionHistoryHttpTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired InspectionRecordRepository records;
    @MockitoBean AiJudgmentService judge;

    private static final String RESULT = """
            {"request":{"adText":"광고"},"productInfoSource":"UNVERIFIED",
             "layer2":{"dataAsOf":"2026-10-01","findings":[
                {"verdict":"VIOLATION","sentenceIndexes":[0,1]},{"verdict":"REVIEW"},{"verdict":"OK"}]}}
            """;
    private static final LocalDateTime DATE = LocalDateTime.of(2026, 11, 12, 12, 0);

    private User user() {
        return users.saveAndFlush(new User("history-" + UUID.randomUUID() + "@test.example", "fixture", null));
    }

    private InspectionRecord record(User user, String name, String ad, String status, String result, LocalDateTime date) {
        return records.saveAndFlush(new InspectionRecord(user.getId(), null, name, null, null, null,
                ad, status, result, date));
    }

    @Test
    void returnsSavedCountsDateAndPreviousLinkWithoutExposingRawJsonOrCallingAi() throws Exception {
        var user = user();
        var before = record(user, "이전 제품", "광고", "DONE", RESULT, DATE);
        var after = new InspectionRecord(user.getId(), null, "수정 제품", null, null, null,
                "수정 광고", "DONE", RESULT, DATE.plusMinutes(1));
        after.linkToPreviousInspection(before.getId());
        records.saveAndFlush(after);
        var auth = jwt().jwt(j -> j.subject(user.getId().toString()));
        mvc.perform(get("/api/inspections").with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].jobId").value(after.getId().toString()))
                .andExpect(jsonPath("$.items[0].productName").value("수정 제품"))
                .andExpect(jsonPath("$.items[0].countsAvailable").value(true))
                .andExpect(jsonPath("$.items[0].violationCount").value(1))
                .andExpect(jsonPath("$.items[0].reviewCount").value(1))
                .andExpect(jsonPath("$.items[0].dataAsOf").value("2026-10-01"))
                .andExpect(jsonPath("$.items[0].previousJobId").value(before.getId().toString()))
                .andExpect(jsonPath("$.items[0].resultJson").doesNotExist());
        mvc.perform(get("/api/inspections/" + after.getId()).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.layer2.dataAsOf").value("2026-10-01"));
        verifyNoInteractions(judge);
    }

    @Test
    void hidesOtherUsersRecordsAndKeepsStableOrderAcrossPages() throws Exception {
        var owner = user();
        var other = user();
        var older = record(owner, "첫 기록", "광고", "DONE", RESULT, DATE);
        var newer = record(owner, "다음 기록", "광고", "DONE", RESULT, DATE);
        var hidden = record(other, "다른 회원", "광고", "DONE", RESULT, DATE.plusDays(1));
        var auth = jwt().jwt(j -> j.subject(owner.getId().toString()));
        mvc.perform(get("/api/inspections?size=1&page=0").with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].jobId").value(newer.getId().toString()));
        mvc.perform(get("/api/inspections?size=1&page=1").with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].jobId").value(older.getId().toString()));
        mvc.perform(get("/api/inspections/" + hidden.getId()).with(auth)).andExpect(status().isNotFound());
        mvc.perform(get("/api/inspections")).andExpect(status().isUnauthorized());
        verifyNoInteractions(judge);
    }

    @Test
    void brokenAndFailedResultsHaveUnknownCountsAndDoNotBreakThePage() throws Exception {
        var owner = user();
        record(owner, null, "광고", "DONE", "broken", DATE);
        record(owner, null, "광고", "TIMEOUT", "{}", DATE.plusMinutes(1));
        mvc.perform(get("/api/inspections").with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].status").value("TIMEOUT"))
                .andExpect(jsonPath("$.items[0].countsAvailable").value(false))
                .andExpect(jsonPath("$.items[0].violationCount").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].reviewCount").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[1].countsAvailable").value(false));
        verifyNoInteractions(judge);
    }

    @Test
    void keepsExistingPageLimitsAndUnicodePreview() throws Exception {
        var owner = user();
        record(owner, null, "😀".repeat(41), "DONE", RESULT, DATE);
        var auth = jwt().jwt(j -> j.subject(owner.getId().toString()));
        mvc.perform(get("/api/inspections?page=-1&size=999").with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.items[0].adTextPreview").value("😀".repeat(40) + "…"));
        mvc.perform(get("/api/inspections?size=0").with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1));
        mvc.perform(get("/api/inspections?page=10").with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.totalElements").value(1));
    }
}
