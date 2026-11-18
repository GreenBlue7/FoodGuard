package com.my.food_ad_check.controller;

import com.my.food_ad_check.service.InspectionInputException;
import com.my.food_ad_check.service.InspectionInputValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes=InspectionJobController.class)
public class InspectionInputHandler {
    public record Rejection(String code, String message, InspectionInputValidator.Result layer1) { }

    @ExceptionHandler(InspectionInputException.class)
    public ResponseEntity<Rejection> invalid(InspectionInputException failure) {
        return ResponseEntity.status(failure.getStatusCode())
                .body(new Rejection(failure.code(), failure.getReason(), failure.validation()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Rejection> malformed() {
        return invalid(new InspectionInputException(HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST_BODY", "검사 요청 형식을 확인해 주세요.", null));
    }
}
