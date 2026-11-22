package com.my.food_ad_check.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class NotificationEmailSender {
    private final JavaMailSender sender;
    private final boolean enabled;
    private final String from;

    public NotificationEmailSender(JavaMailSender sender,
            @Value("${notification-email.delivery-enabled:false}") boolean enabled,
            @Value("${notification-email.from:foodguard@localhost}") String from) {
        this.sender = sender;
        this.enabled = enabled;
        this.from = from;
    }
    public void requireEnabled() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이메일 발송이 아직 설정되지 않았습니다.");
    }
    public void send(String recipient, String subject, String body) {
        requireEnabled();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }
}
