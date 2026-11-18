package com.my.food_ad_check.controller;

import com.my.food_ad_check.service.InspectionAdmissionException;
import com.my.food_ad_check.service.InspectionInputValidator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = InspectionJobController.class)
public class InspectionAdmissionHandler {
    public record Rejection(String code, String message, int retryAfterSeconds, InspectionInputValidator.Result layer1) { }

    @ExceptionHandler(InspectionAdmissionException.class)
    public ResponseEntity<Rejection> rejected(InspectionAdmissionException failure) {
        return ResponseEntity.status(429)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(failure.retryAfterSeconds()))
                .body(new Rejection(failure.code(), failure.getMessage(), failure.retryAfterSeconds(),
                        InspectionInputValidator.rejected(failure.code(), failure.getMessage(), null)));
    }
}
