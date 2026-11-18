package com.my.food_ad_check.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class InspectionInputException extends ResponseStatusException {
    private final String code;
    private final InspectionInputValidator.Result validation;
    public InspectionInputException(HttpStatus status, String code, String message, Integer count) {
        super(status, message);
        this.code = code;
        this.validation = InspectionInputValidator.rejected(code, message, count);
    }
    public String code() { return code; }
    public InspectionInputValidator.Result validation() { return validation; }
}
