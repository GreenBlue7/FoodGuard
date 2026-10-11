package com.my.food_ad_check.dto;

import java.util.List;

/** Matches the JSON returned by judge.app.schemas.JudgeResponse. */
public record FastApiResponse(List<Sentence> sentences, List<Result> results, String model,
                              String kbVersion, Long elapsedMs) {

    public record Sentence(String id, String text, Integer start, Integer end) {
    }

    public record Span(Integer start, Integer end) {
    }

    public record Evidence(String id, String title, String url) {
    }

    public record Result(List<String> sentenceIds, String text, String status,
                         String violationType, String reason, List<Evidence> evidence,
                         Span span, String revision) {
    }
}
