package com.my.food_ad_check.config;

import com.my.food_ad_check.service.InspectionJobWorker;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="inspection.jobs.worker-enabled", havingValue="true", matchIfMissing=true)
public class InspectionJobScheduling {
    private final InspectionJobWorker worker;
    public InspectionJobScheduling(InspectionJobWorker worker) { this.worker = worker; }

    @Bean
    public ThreadPoolTaskScheduler inspectionJobTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("inspection-worker-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("maintenance-");
        return scheduler;
    }

    // A separate scheduler keeps long AI requests from blocking product refresh or mail delivery.
    @Scheduled(fixedDelayString="${inspection.jobs.poll-ms:1000}", initialDelayString="${inspection.jobs.poll-ms:1000}",
               scheduler="inspectionJobTaskScheduler")
    public void poll() {
        try { worker.processOne(); }
        catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("검사 처리 작업 실패: {}", failure.getClass().getSimpleName());
        }
    }
}
