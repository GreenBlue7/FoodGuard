package com.my.food_ad_check.controller;

import com.my.food_ad_check.service.ProductNotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class ProductNotificationController {
    private final ProductNotificationService service;
    public ProductNotificationController(ProductNotificationService service) { this.service = service; }

    @GetMapping
    public ProductNotificationService.NotificationPage list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return service.list(Long.valueOf(jwt.getSubject()), page, size, unreadOnly);
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        service.markRead(Long.valueOf(jwt.getSubject()), id);
    }
}
