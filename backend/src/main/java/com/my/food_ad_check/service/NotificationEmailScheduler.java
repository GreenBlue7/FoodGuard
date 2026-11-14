package com.my.food_ad_check.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.LoggerFactory;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="notification-email.delivery-enabled",havingValue="true")
public class NotificationEmailScheduler {
    private final NotificationEmailQueue queue;
    public NotificationEmailScheduler(NotificationEmailQueue queue) { this.queue=queue; }
    @Scheduled(fixedDelayString="${notification-email.poll-ms:60000}",initialDelayString="${notification-email.poll-ms:60000}")
    public void dispatch() {
        try { queue.dispatch(); }
        catch (RuntimeException failure) { LoggerFactory.getLogger(getClass()).warn("이메일 발송 작업 실패: {}",failure.getClass().getSimpleName()); }
    }
}
