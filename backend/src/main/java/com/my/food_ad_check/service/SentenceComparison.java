package com.my.food_ad_check.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.*;

/** Deterministic source matching. No model calls and no inference that deleted claims were fixed. */
public final class SentenceComparison {
    private static final Set<String> STATUSES = Set.of("OK", "HOLD", "REVIEW", "VIOLATION");
    private static final int MAX_SENTENCES = 1000;
    public record Edit(String previousSentenceId, int currentStart, int currentEnd) { }
    private record Sentence(int index, String id, String text, int start, int end, String status,
                            List<String> violationTypes, List<String> reasons) { }
    private record Match(int after, String method, boolean certain) { }
    private SentenceComparison() { }

    /** Optional edit hints express the user's editing intent, but must refer to actual source ranges. */
    public static void validateEdits(JsonNode previous, String beforeText, String afterText, List<Edit> edits) {
        if (edits == null || edits.isEmpty()) return;
        if (edits.size() > MAX_SENTENCES) throw new IllegalArgumentException("수정 문장 정보가 너무 많습니다.");
        List<Sentence> old = sentences(previous, beforeText);
        Set<String> ids = new HashSet<>();
        List<Edit> byRange = new ArrayList<>(edits);
        for (Edit edit : byRange) {
            if (edit == null || edit.previousSentenceId() == null || !ids.add(edit.previousSentenceId())
                    || old.stream().noneMatch(s -> s.id().equals(edit.previousSentenceId()))
                    || edit.currentStart() < 0 || edit.currentEnd() <= edit.currentStart()
                    || edit.currentEnd() > afterText.codePointCount(0, afterText.length())) {
                throw new IllegalArgumentException("수정 문장 번호 또는 현재 원문 위치가 올바르지 않습니다.");
            }
            String text = afterText.substring(afterText.offsetByCodePoints(0, edit.currentStart()),
                    afterText.offsetByCodePoints(0, edit.currentEnd()));
            if (text.isBlank()) throw new IllegalArgumentException("수정 문장 위치가 공백만 가리킵니다.");
        }
        byRange.sort(Comparator.comparingInt(Edit::currentStart));
        for (int i = 1; i < byRange.size(); i++) {
            if (byRange.get(i).currentStart() < byRange.get(i - 1).currentEnd()) {
                throw new IllegalArgumentException("수정 문장 위치가 서로 겹칩니다.");
            }
        }
    }

    public static Map<String, Object> compare(JsonNode before, String beforeText, JsonNode after,
                                               boolean sameCriteria, String previousJobId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sentenceComparisonAvailable", false);
        result.put("sentenceChanges", List.of());
        result.put("sentenceSummary", null);
        try {
            String afterText = after.path("request").path("adText").asText(null);
            List<Sentence> old = sentences(before, beforeText);
            List<Sentence> current = sentences(after, afterText);
            Map<Integer, Match> matches = new HashMap<>();
            Set<Integer> usedCurrent = new HashSet<>();

            // Explicit edit ranges are reserved before matching duplicate source sentences.
            JsonNode hints = after.path("sentenceEditMappings");
            if (hints.isArray()) for (JsonNode hint : hints) {
                String id = hint.path("previousSentenceId").asText();
                for (Sentence left : old) if (left.id().equals(id)) {
                    for (Sentence right : current) {
                        if (right.start() == hint.path("currentStart").asInt(-1)
                                && right.end() == hint.path("currentEnd").asInt(-1)
                                && !matches.containsKey(left.index()) && usedCurrent.add(right.index())) {
                            matches.put(left.index(), new Match(right.index(), "USER_EDIT_RANGE", true));
                        }
                    }
                }
            }
            reserveRecommendation(before, beforeText, after, afterText, old, current, previousJobId, matches, usedCurrent);

            Map<String, Long> oldCounts = frequencies(old.stream().filter(s -> !matches.containsKey(s.index())).toList());
            Map<String, Long> currentCounts = frequencies(current.stream().filter(s -> !usedCurrent.contains(s.index())).toList());
            Map<String, Deque<Sentence>> byText = new HashMap<>();
            for (Sentence right : current) if (!usedCurrent.contains(right.index())) {
                byText.computeIfAbsent(key(right.text()), k -> new ArrayDeque<>()).add(right);
            }
            for (Sentence left : old) {
                if (matches.containsKey(left.index())) continue;
                String textKey = key(left.text());
                Deque<Sentence> candidates = byText.get(textKey);
                if (candidates != null && !candidates.isEmpty()) {
                    Sentence right = candidates.removeFirst();
                    boolean certain = oldCounts.get(textKey) == 1 && currentCounts.get(textKey) == 1;
                    matches.put(left.index(), new Match(right.index(), certain ? "EXACT_TEXT" : "DUPLICATE_OCCURRENCE", certain));
                    usedCurrent.add(right.index());
                }
            }

            // Similarity is a display aid only. It cannot establish that a particular issue was resolved.
            List<Sentence> unmatchedOld = old.stream().filter(s -> !matches.containsKey(s.index())).toList();
            List<Sentence> unmatchedCurrent = current.stream().filter(s -> !usedCurrent.contains(s.index())).toList();
            if ((long) unmatchedOld.size() * unmatchedCurrent.size() <= 10_000) {
                var leftGrams = unmatchedOld.stream().map(s -> similarityGrams(s.text())).toList();
                var rightGrams = unmatchedCurrent.stream().map(s -> similarityGrams(s.text())).toList();
                double[][] scores = new double[unmatchedOld.size()][unmatchedCurrent.size()];
                for (int i = 0; i < unmatchedOld.size(); i++) for (int j = 0; j < unmatchedCurrent.size(); j++) {
                    scores[i][j] = similarity(leftGrams.get(i), rightGrams.get(j));
                }
                for (int i = 0; i < unmatchedOld.size(); i++) {
                    int best = uniqueBest(scores[i]);
                    if (best < 0) continue;
                    double[] column = new double[unmatchedOld.size()];
                    for (int k = 0; k < unmatchedOld.size(); k++) column[k] = scores[k][best];
                    if (uniqueBest(column) == i) {
                        int right = unmatchedCurrent.get(best).index();
                        matches.put(unmatchedOld.get(i).index(), new Match(right, "SIMILAR_TEXT", false));
                        usedCurrent.add(right);
                    }
                }
            }

            List<Map<String, Object>> changes = new ArrayList<>();
            Map<String, Integer> summary = new LinkedHashMap<>();
            for (String name : List.of("unchanged", "modified", "added", "removed", "resolvedThisCheck", "remainingIssues",
                    "newIssues", "unchangedVerdictChanges", "uncertainMappings", "notComparable")) summary.put(name, 0);
            for (Sentence left : old) {
                Match match = matches.get(left.index());
                Sentence right = match == null ? null : current.get(match.after());
                changes.add(change(left, right, match, sameCriteria, summary));
            }
            for (Sentence right : current) if (!usedCurrent.contains(right.index())) {
                changes.add(change(null, right, null, sameCriteria, summary));
            }
            result.put("sentenceComparisonAvailable", true);
            result.put("sentenceChanges", changes);
            result.put("sentenceSummary", summary);
            result.put("editHintsNotMatched", hints.isArray() ? hints.size() - (int) matches.values().stream()
                    .filter(m -> m.method().equals("USER_EDIT_RANGE")).count() : 0);
            result.put("sentenceComparisonNote", "추가·삭제는 원문 기준입니다. 유사 문장 연결은 확인이 필요하며, 삭제를 문제 해결로 집계하지 않습니다.");
        } catch (IllegalArgumentException e) {
            result.put("sentenceComparisonNote", "문장별 원문·위치 정보를 확인할 수 없어 항목 비교를 제공하지 못했습니다.");
        }
        return result;
    }

    private static Map<String, Object> change(Sentence before, Sentence after, Match match, boolean sameCriteria,
                                             Map<String, Integer> summary) {
        String type = before == null ? "ADDED" : after == null ? "REMOVED"
                : key(before.text()).equals(key(after.text())) ? "UNCHANGED" : "MODIFIED";
        summary.merge(type.toLowerCase(Locale.ROOT), 1, Integer::sum);
        String assessment;
        if (!sameCriteria) assessment = "CRITERIA_CHANGED_OR_UNKNOWN";
        else if ((before != null && before.status().equals("UNKNOWN")) || (after != null && after.status().equals("UNKNOWN"))) {
            assessment = "RESULT_UNAVAILABLE";
        } else if (after == null) assessment = "REMOVED_NOT_ASSESSED";
        else if (match != null && !match.certain()) assessment = "MAPPING_REQUIRES_CONFIRMATION";
        else if (before == null) assessment = risky(after) ? "NEW_ISSUE" : "NO_ISSUE";
        else if (type.equals("UNCHANGED") && (!before.status().equals(after.status())
                || !before.violationTypes().equals(after.violationTypes()))) assessment = "UNCHANGED_VERDICT_CHANGED";
        else if (risky(before) && !risky(after)) assessment = "RESOLVED_THIS_CHECK";
        else if (risky(after)) assessment = risky(before) ? "REMAINING_ISSUE" : "NEW_ISSUE";
        else assessment = "NO_ISSUE";

        if (match != null && !match.certain()) summary.merge("uncertainMappings", 1, Integer::sum);
        switch (assessment) {
            case "RESOLVED_THIS_CHECK" -> summary.merge("resolvedThisCheck", 1, Integer::sum);
            case "REMAINING_ISSUE" -> summary.merge("remainingIssues", 1, Integer::sum);
            case "NEW_ISSUE" -> summary.merge("newIssues", 1, Integer::sum);
            case "UNCHANGED_VERDICT_CHANGED" -> {
                summary.merge("unchangedVerdictChanges", 1, Integer::sum);
                if (risky(before) && risky(after)) summary.merge("remainingIssues", 1, Integer::sum);
            }
            case "CRITERIA_CHANGED_OR_UNKNOWN", "RESULT_UNAVAILABLE", "MAPPING_REQUIRES_CONFIRMATION" -> summary.merge("notComparable", 1, Integer::sum);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("changeType", type);
        item.put("before", before == null ? null : view(before));
        item.put("after", after == null ? null : view(after));
        item.put("mappingMethod", match == null ? "UNMATCHED" : match.method());
        item.put("mappingCertain", match != null && match.certain());
        item.put("issueChange", assessment);
        return item;
    }

    private static Map<String, Object> view(Sentence s) {
        return Map.of("index", s.index(), "id", s.id(), "text", s.text(), "range", Map.of("start", s.start(), "end", s.end()),
                "verdict", s.status(), "violationTypes", s.violationTypes(), "reasons", s.reasons());
    }

    private static List<Sentence> sentences(JsonNode job, String adText) {
        JsonNode source = job.path("judgeResponse").path("sentences");
        JsonNode results = job.path("judgeResponse").path("results");
        if (adText == null || !source.isArray() || !results.isArray() || source.size() > MAX_SENTENCES
                || (source.isEmpty() && !adText.isBlank())) throw new IllegalArgumentException("Missing sentence data");
        int length = adText.codePointCount(0, adText.length());
        List<Sentence> sentences = new ArrayList<>();
        Map<String, List<JsonNode>> bySentence = new HashMap<>();
        int references = 0;
        for (JsonNode verdict : results) for (JsonNode ref : verdict.path("sentenceIds")) {
            if (++references > 100_000) throw new IllegalArgumentException("Too many result references");
            bySentence.computeIfAbsent(ref.asText(), k -> new ArrayList<>()).add(verdict);
        }
        Set<String> ids = new HashSet<>();
        int previousEnd = 0;
        for (JsonNode node : source) {
            String id = node.path("id").asText("");
            String text = node.path("text").asText(null);
            int start = node.path("start").asInt(-1), end = node.path("end").asInt(-1);
            if (id.isBlank() || !ids.add(id) || text == null || text.isBlank() || !node.path("start").isIntegralNumber()
                    || !node.path("end").isIntegralNumber() || !node.path("start").canConvertToInt()
                    || !node.path("end").canConvertToInt() || start < previousEnd || end <= start || end > length
                    || !text.equals(adText.substring(adText.offsetByCodePoints(0, start), adText.offsetByCodePoints(0, end)))) {
                throw new IllegalArgumentException("Invalid sentence range");
            }
            if (!blank(adText.substring(adText.offsetByCodePoints(0, previousEnd), adText.offsetByCodePoints(0, start)))) {
                throw new IllegalArgumentException("Uncovered text between sentences");
            }
            previousEnd = end;
            Set<String> statuses = new HashSet<>();
            Set<String> types = new TreeSet<>();
            Set<String> reasons = new LinkedHashSet<>();
            for (JsonNode result : bySentence.getOrDefault(id, List.of())) {
                statuses.add(result.path("status").asText("UNKNOWN"));
                String type = result.path("violationType").asText("");
                if (!type.isBlank()) types.add(type);
                String reason = result.path("reason").asText("");
                if (!reason.isBlank()) reasons.add(reason);
            }
            String status = statuses.isEmpty() || !STATUSES.containsAll(statuses) ? "UNKNOWN"
                    : statuses.contains("VIOLATION") ? "VIOLATION" : statuses.contains("REVIEW") ? "REVIEW"
                    : statuses.contains("HOLD") ? "HOLD" : "OK";
            sentences.add(new Sentence(sentences.size(), id, text, start, end, status, List.copyOf(types), List.copyOf(reasons)));
        }
        if (!blank(adText.substring(adText.offsetByCodePoints(0, previousEnd)))) {
            throw new IllegalArgumentException("Uncovered trailing text");
        }
        return sentences;
    }

    private static void reserveRecommendation(JsonNode before, String beforeText, JsonNode after, String afterText,
            List<Sentence> old, List<Sentence> current, String previousJobId, Map<Integer, Match> matches, Set<Integer> used) {
        JsonNode validation = after.path("recommendationValidation");
        String id = validation.path("recommendationId").asText("");
        if (!previousJobId.equals(validation.path("sourceJobId").asText()) || !id.matches("finding-\\d{1,5}")) return;
        int index = Integer.parseInt(id.substring("finding-".length()));
        JsonNode item = before.path("judgeResponse").path("results").path(index);
        if (item.path("sentenceIds").size() != 1 || item.path("revision").asText("").isBlank()) return;
        for (Sentence left : old) if (left.id().equals(item.path("sentenceIds").path(0).asText()) && !matches.containsKey(left.index())) {
            String revision = item.path("revision").asText().trim();
            String expected = beforeText.substring(0, beforeText.offsetByCodePoints(0, left.start())) + revision
                    + beforeText.substring(beforeText.offsetByCodePoints(0, left.end()));
            if (!expected.equals(afterText)) return;
            int end = left.start() + revision.codePointCount(0, revision.length());
            for (Sentence right : current) if (right.start() == left.start() && right.end() == end && !used.contains(right.index())) {
                matches.put(left.index(), new Match(right.index(), "SELECTED_RECOMMENDATION", true));
                used.add(right.index());
            }
        }
    }

    private static Map<String, Long> frequencies(List<Sentence> sentences) {
        Map<String, Long> counts = new HashMap<>();
        for (Sentence s : sentences) counts.merge(key(s.text()), 1L, Long::sum);
        return counts;
    }
    private static boolean risky(Sentence sentence) { return !"OK".equals(sentence.status()); }
    private static String key(String text) { return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("(?U)\\s+", " ").strip(); }
    private static boolean blank(String text) { return text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)); }

    private static int uniqueBest(double[] scores) {
        int best = -1;
        double top = 0, second = 0;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > top) { second = top; top = scores[i]; best = i; }
            else second = Math.max(second, scores[i]);
        }
        return top >= 0.60 && top - second >= 0.15 ? best : -1;
    }
    private static double similarity(Set<Long> l, Set<Long> r) {
        if (l.isEmpty() || r.isEmpty()) return 0;
        long common = l.stream().filter(r::contains).count();
        return 2.0 * common / (l.size() + r.size());
    }
    private static Set<Long> similarityGrams(String text) {
        int[] points = key(text).codePoints().toArray();
        return points.length < 6 || points.length > 512 ? Set.of() : bigrams(points);
    }
    private static Set<Long> bigrams(int[] codepoints) {
        Set<Long> grams = new HashSet<>();
        for (int i = 1; i < codepoints.length; i++) grams.add(((long) codepoints[i - 1] << 32) | codepoints[i]);
        return grams;
    }
}
