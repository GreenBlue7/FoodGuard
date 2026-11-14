package com.my.food_ad_check.service;

import com.my.food_ad_check.entity.InspectionRecord;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class InspectionReportRendererTests {
    private InspectionRecord record(String result){return new InspectionRecord(1L,"SAVED-REPORT","검사 당시 제품","당시 제조사",
            "건강기능식품","당시 기능성 원문","광고 원문 <script>alert(1)</script>","DONE",result,LocalDateTime.of(2026,11,12,10,0));}

    @Test void highlightsProblemsFirstAndLabelsSimulationAndUnverifiedRevisions() throws Exception {
        String fixture=new ClassPathResource("report-demo.json").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String html=InspectionReportRenderer.render(record(fixture));
        assertTrue(html.contains("시연 데이터 · 실제 AI 검수 결과가 아닙니다."));
        assertTrue(html.contains("검토 결과 요약"));
        assertTrue(html.contains("검토 의견 및 근거"));
        assertTrue(html.contains("대상 문구"));
        assertTrue(html.indexOf("[시연] 이 제품으로 질병이 치료됩니다.")<html.indexOf("[시연] 누구에게나 효과가 있습니다."));
        assertTrue(html.contains("수정 예시는 별도 재검수 전까지 적합성이 확인된 문구가 아닙니다."));
        assertTrue(html.contains("2026-10-01"));assertTrue(html.contains("검사 당시 제품"));
        assertTrue(html.contains("@media print"));assertTrue(html.contains("@page{size:A4"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
    }

    @Test void escapesAllResultTextAndRejectsExecutableEvidenceLinks() {
        String html=InspectionReportRenderer.render(record("""
                {"judgeResponse":{"model":"old","kbVersion":"none","results":[
                 {"status":"VIOLATION","text":"<img src=x onerror=alert(1)>","reason":"<script>bad()</script>",
                  "revision":"<svg onload=bad()>","evidence":[{"title":"<b>資料</b>","url":"javascript:alert(1)"},
                  {"title":"안전한 링크","url":"https://example.test/case?a=1&b=2"}]}]}}
                """));
        assertTrue(html.contains("&lt;img"));assertTrue(html.contains("&lt;script&gt;bad()&lt;/script&gt;"));
        assertFalse(html.contains("href='javascript:"));assertFalse(html.contains("<img src=x"));
        assertTrue(html.contains("https://example.test/case?a=1&amp;b=2"));
        assertTrue(html.contains("자료 기준일 기록이 없습니다"));
    }

    @Test void brokenMissingAndUnknownVerdictsCannotBecomeAZeroProblemReport() {
        for(String result:new String[]{"broken","null","{}","{\"layer2\":{\"findings\":[{\"verdict\":\"UNKNOWN\"}]}}"})
            assertEquals(422,assertThrows(ResponseStatusException.class,()->InspectionReportRenderer.render(record(result))).getStatusCode().value());
        String zero=InspectionReportRenderer.render(record("{\"layer2\":{\"findings\":[]}}"));
        assertTrue(zero.contains("저장된 판정 항목이 0건"));
    }

    @Test void changedCriteriaDoNotClaimResolutionAndDeletedSentencesAreSeparate() {
        String html=InspectionReportRenderer.render(record("""
                {"layer2":{"findings":[]},"comparison":{"previousJobId":"3","sameRecordedCriteria":false,
                 "sentenceComparisonAvailable":true,"sentenceSummary":{"resolvedThisCheck":5},"sentenceChanges":[
                 {"issueChange":"RESOLVED_THIS_CHECK","before":{"text":"이전 문장"},"after":{"text":"수정 문장"}},
                 {"issueChange":"REMOVED_NOT_ASSESSED","before":{"text":"삭제 문장"},"after":null}]}}
                """));
        String readable=org.springframework.web.util.HtmlUtils.htmlUnescape(html);
        assertTrue(readable.contains("기준 변경 · 해소 확정 불가"));assertTrue(readable.contains("삭제 · 해결 집계 제외"));
        assertFalse(html.contains("이번 검사에서 해소 5"));
    }

    @Test void incompleteSentenceReferencesDoNotShowAPartialQuoteAsTheFullFinding() {
        String html=InspectionReportRenderer.render(record("""
                {"layer2":{"sentences":[{"text":"부분 원문"}],"findings":[
                  {"verdict":"VIOLATION","sentenceIndexes":[0,99],"reason":"이유"}]}}
                """));
        assertTrue(html.contains("지적 문장의 원문 기록 없음"));
        assertFalse(html.contains("부분 원문"));
    }
}
