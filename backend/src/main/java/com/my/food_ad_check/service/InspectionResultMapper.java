package com.my.food_ad_check.service;

import com.my.food_ad_check.dto.FastApiResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts the judge's existing response into the fields the Vue result view can use. */
public final class InspectionResultMapper {

    private static final String MANDATORY_REASON = "법정 의무 문구입니다.";
    private static final Pattern CASE_TITLE = Pattern.compile(
            "^'(.*)' 적발 \\(.*?, 식약처 (\\d{4})-\\d{2}-\\d{2}\\)$");

    private InspectionResultMapper() {
    }

    public static Map<String, Object> toLayer2(FastApiResponse response, String judgedAt) {
        Map<String, Integer> sentenceIndexes = new HashMap<>();
        Map<String, FastApiResponse.Result> resultsBySentence = new HashMap<>();
        for (FastApiResponse.Result result : response.results()) {
            if (result.sentenceIds() != null) {
                for (String id : result.sentenceIds()) {
                    resultsBySentence.putIfAbsent(id, result);
                }
            }
        }

        List<Map<String, Object>> sentences = new ArrayList<>();
        for (int index = 0; index < response.sentences().size(); index++) {
            FastApiResponse.Sentence sentence = response.sentences().get(index);
            sentenceIndexes.put(sentence.id(), index);
            FastApiResponse.Result result = resultsBySentence.get(sentence.id());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", index);
            item.put("text", sentence.text());
            item.put("range", range(sentence.start(), sentence.end()));
            item.put("isMandatory", result != null && "OK".equals(result.status())
                    && MANDATORY_REASON.equals(result.reason()));
            sentences.add(item);
        }

        List<Map<String, Object>> findings = new ArrayList<>();
        for (int index = 0; index < response.results().size(); index++) {
            FastApiResponse.Result result = response.results().get(index);
            List<Integer> indexes = result.sentenceIds() == null ? List.of()
                    : result.sentenceIds().stream().map(sentenceIndexes::get)
                            .filter(java.util.Objects::nonNull).distinct().toList();
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("id", "finding-" + index);
            // The Vue contract has no HOLD verdict; keep the original in judgeResponse.
            finding.put("verdict", "HOLD".equals(result.status()) ? "REVIEW" : result.status());
            finding.put("sentenceIndexes", indexes);
            finding.put("reason", result.reason());
            finding.put("lawRefs", lawRefs(result.violationType()));
            finding.put("cases", caseRefs(result.evidence()));
            // The judge does not expose its testimonial tag in JudgeResponse.
            finding.put("isTestimonial", null);
            finding.put("violationType", result.violationType());
            finding.put("evidence", result.evidence());
            finding.put("revision", result.revision());
            finding.put("span", result.span());
            findings.add(finding);
        }

        Map<String, Object> layer2 = new LinkedHashMap<>();
        layer2.put("sentences", sentences);
        layer2.put("findings", findings);
        layer2.put("model", response.model());
        layer2.put("dataAsOf", caseDataDate(response.kbVersion()));
        layer2.put("judgedAt", judgedAt);
        return layer2;
    }

    private static Map<String, Integer> range(Integer start, Integer end) {
        Map<String, Integer> range = new LinkedHashMap<>();
        range.put("start", start);
        range.put("end", end);
        return range;
    }

    private static List<Map<String, Object>> lawRefs(String article) {
        if (article == null || article.isBlank()) {
            return List.of();
        }
        Map<String, Object> law = new LinkedHashMap<>();
        law.put("id", article);
        law.put("title", "식품 등의 표시·광고에 관한 법률");
        law.put("article", article);
        law.put("text", ""); // The judge does not return the article text or a summary.
        return List.of(law);
    }

    private static List<Map<String, Object>> caseRefs(List<FastApiResponse.Evidence> evidence) {
        if (evidence == null) {
            return List.of();
        }
        List<Map<String, Object>> cases = new ArrayList<>();
        for (FastApiResponse.Evidence item : evidence) {
            if (item.title() == null) {
                continue;
            }
            Matcher match = CASE_TITLE.matcher(item.title());
            if (!match.matches()) {
                continue;
            }
            Map<String, Object> caseRef = new LinkedHashMap<>();
            caseRef.put("id", item.id());
            caseRef.put("year", Integer.parseInt(match.group(2)));
            caseRef.put("excerpt", match.group(1));
            caseRef.put("source", item.url() == null || item.url().isBlank() ? "식약처" : item.url());
            cases.add(caseRef);
        }
        return cases;
    }

    private static String caseDataDate(String kbVersion) {
        if (kbVersion == null) {
            return null;
        }
        try {
            return LocalDate.parse(kbVersion).toString();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
