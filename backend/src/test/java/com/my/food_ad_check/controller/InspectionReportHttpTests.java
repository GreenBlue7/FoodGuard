package com.my.food_ad_check.controller;

import com.my.food_ad_check.entity.*;
import com.my.food_ad_check.repository.*;
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
class InspectionReportHttpTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired InspectionRecordRepository records;
    @MockitoBean AiJudgmentService judge;
    private User user(){return users.saveAndFlush(new User("report-"+UUID.randomUUID()+"@test.example","fixture",null));}
    private InspectionRecord record(User user,String status,String result){return records.saveAndFlush(new InspectionRecord(user.getId(),null,
            "검사 당시 제품명",null,null,null,"검사 당시 광고",status,result,LocalDateTime.of(2026,11,12,10,0)));}

    @Test void onlyOwnerCanReadStoredReportAndNoAiIsCalled() throws Exception {
        var owner=user();var other=user();
        var record=record(owner,"DONE","{\"layer2\":{\"dataAsOf\":\"2026-10-01\",\"findings\":[]}}");
        String path="/api/inspections/"+record.getId()+"/report";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt().jwt(j->j.subject(other.getId().toString())))).andExpect(status().isNotFound());
        mvc.perform(get(path).with(jwt().jwt(j->j.subject(owner.getId().toString()))))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("검사 당시 제품명")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-10-01")));
        verifyNoInteractions(judge);
    }

    @Test void failedJobsAndCorruptCompletedJobsAreNotExported() throws Exception {
        var owner=user();var auth=jwt().jwt(j->j.subject(owner.getId().toString()));
        for(String status:new String[]{"FAILED","TIMEOUT","PENDING","RUNNING"}){
            var record=record(owner,status,"{}");
            mvc.perform(get("/api/inspections/"+record.getId()+"/report").with(auth)).andExpect(status().isConflict());
        }
        var broken=record(owner,"DONE","broken");
        mvc.perform(get("/api/inspections/"+broken.getId()+"/report").with(auth)).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(judge);
    }

    @Test void publicLauncherHasNoPrivateContentAndKeepsRestrictedContentPolicy() throws Exception {
        var owner=user();var record=record(owner,"DONE","{\"layer2\":{\"findings\":[]}}");
        mvc.perform(get("/reports/"+record.getId())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Content-Security-Policy",org.hamcrest.Matchers.containsString("sha256-")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("검사 당시 광고"))));
        verifyNoInteractions(judge);
    }
}
