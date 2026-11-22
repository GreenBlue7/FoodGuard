package com.my.food_ad_check.service;

/** An unaccepted request, distinct from a queued job whose judgment later failed. */
public class InspectionAdmissionException extends RuntimeException {
    private final String code;
    private final int retryAfterSeconds;

    public InspectionAdmissionException(String code, String message, int retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public String code() { return code; }
    public int retryAfterSeconds() { return retryAfterSeconds; }
}
