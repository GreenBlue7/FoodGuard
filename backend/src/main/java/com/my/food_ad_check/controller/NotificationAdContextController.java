package com.my.food_ad_check.controller;

import com.my.food_ad_check.service.NotificationAdContextService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationAdContextController {
    private final NotificationAdContextService service;
    public NotificationAdContextController(NotificationAdContextService service) { this.service = service; }

    @GetMapping("/{id}/ad-context")
    public NotificationAdContextService.Context get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return service.get(Long.valueOf(jwt.getSubject()), id);
    }
}
