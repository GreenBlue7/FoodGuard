package com.my.food_ad_check.controller;

import com.my.food_ad_check.service.OperationsStatusService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationsStatusController {
    private final OperationsStatusService status;
    public OperationsStatusController(OperationsStatusService status) { this.status = status; }

    @GetMapping("/api/operations/status")
    public ResponseEntity<OperationsStatusService.Snapshot> get() {
        var snapshot = status.snapshot();
        return ResponseEntity.status("UNAVAILABLE".equals(snapshot.status()) ? 503 : 200)
                .cacheControl(CacheControl.noStore()).body(snapshot);
    }
}
