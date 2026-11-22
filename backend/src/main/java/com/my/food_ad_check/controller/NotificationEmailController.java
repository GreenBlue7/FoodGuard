package com.my.food_ad_check.controller;

import com.my.food_ad_check.service.NotificationEmailPreferences;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

@RestController
public class NotificationEmailController {
    private final NotificationEmailPreferences preferences;
    private final String publicWeb;
    public record PreferenceRequest(@NotNull Boolean enabled) { }
    public NotificationEmailController(NotificationEmailPreferences preferences,
            @Value("${notification-email.public-web-url:http://127.0.0.1:5173}") String publicWeb) {
        this.preferences=preferences;this.publicWeb=publicWeb;
    }

    @GetMapping("/api/notification-email")
    public NotificationEmailPreferences.Settings settings(@AuthenticationPrincipal Jwt jwt) {
        return preferences.get(Long.valueOf(jwt.getSubject()));
    }
    @PutMapping("/api/notification-email")
    public NotificationEmailPreferences.Settings update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PreferenceRequest body) {
        return preferences.setEnabled(Long.valueOf(jwt.getSubject()),body.enabled());
    }
    @PostMapping("/api/notification-email/verification")
    public ResponseEntity<Void> request(@AuthenticationPrincipal Jwt jwt) {
        preferences.requestVerification(Long.valueOf(jwt.getSubject()));
        return ResponseEntity.accepted().build();
    }

    @GetMapping(value="/email/settings",produces="text/html;charset=UTF-8")
    public ResponseEntity<String> settingsPage() throws IOException { return appPage(null); }
    @GetMapping(value="/email/notifications/{id}",produces="text/html;charset=UTF-8")
    public ResponseEntity<String> notificationPage(@PathVariable Long id) throws IOException { return appPage(id); }

    private ResponseEntity<String> appPage(Long notificationId) throws IOException {
        String nonce=UUID.randomUUID().toString();
        String html=new ClassPathResource("email/notification.html").getContentAsString(StandardCharsets.UTF_8)
                .replace("__NONCE__",nonce).replace("__NOTIFICATION_ID__",notificationId==null?"null":notificationId.toString());
        return page(html,nonce);
    }

    @GetMapping(value="/email/verify",produces="text/html;charset=UTF-8")
    public ResponseEntity<String> verifyPage() {
        String nonce=UUID.randomUUID().toString();
        String html="""
                <!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>FoodGuard 이메일 확인</title><h1>이메일 주소 확인</h1>
                <p>이 주소가 본인의 이메일이면 아래 버튼을 눌러주세요. 주소 확인만으로 알림을 받게 되지는 않습니다.</p>
                <form method="post" action="/email/verify"><input id="token" name="token" type="hidden">
                <button id="verify" disabled>이메일 확인</button></form><p id="message">링크를 확인하는 중입니다.</p>
                <script nonce="__NONCE__">
                const token=new URLSearchParams(location.hash.slice(1)).get('token');
                if(token && /^[A-Za-z0-9_-]{43}$/.test(token)){document.getElementById('token').value=token;
                document.getElementById('verify').disabled=false;document.getElementById('message').textContent='링크는 발송 후 30분 동안 유효합니다.';}
                else document.getElementById('message').textContent='유효한 확인 링크가 아닙니다.';
                history.replaceState(null,'',location.pathname);
                </script></html>
                """.replace("__NONCE__",nonce);
        return page(html,nonce);
    }
    @PostMapping(value="/email/verify",produces="text/html;charset=UTF-8")
    public ResponseEntity<String> verify(@RequestParam String token) {
        preferences.verify(token);
        return page("<!doctype html><html lang=\"ko\"><meta charset=\"utf-8\"><title>이메일 확인 완료</title>"
                +"<h1>이메일 주소를 확인했습니다.</h1><p>로그인 후 이메일 알림을 켜주세요.</p>"
                +"<a href=\"/email/settings\">이메일 알림 설정</a> · <a href=\""+HtmlUtils.htmlEscape(publicWeb)+"\">FoodGuard 열기</a></html>",UUID.randomUUID().toString());
    }
    private ResponseEntity<String> page(String html,String nonce) {
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Referrer-Policy","no-referrer")
                .header("Content-Security-Policy","default-src 'none'; script-src 'nonce-"+nonce+"'; style-src 'nonce-"+nonce+"'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'")
                .body(html);
    }
}
