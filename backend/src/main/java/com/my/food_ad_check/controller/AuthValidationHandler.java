package com.my.food_ad_check.controller;

import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 인증 API의 입력 검증 실패를 직접 처리한다.
// Spring 기본 처리기(DefaultHandlerExceptionResolver)는 실패 내용을 WARN 로그로 남기는데,
// 그 메시지에 거부된 값(rejected value)이 그대로 들어가서 짧은 비밀번호가 평문으로 로그에 찍힌다.
// 여기서 처리하면 기본 처리기가 동작하지 않고, 응답에는 규칙 메시지만 담는다 (입력값은 담지 않음)
@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthValidationHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleInvalidRequest(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(" "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }
}
