package com.my.food_ad_check.controller;

import com.my.food_ad_check.service.InspectionReportService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class InspectionReportController {
    private final InspectionReportService reports;
    public InspectionReportController(InspectionReportService reports){this.reports=reports;}

    @GetMapping(value="/api/inspections/{jobId}/report",produces="text/html;charset=UTF-8")
    public ResponseEntity<String> report(@AuthenticationPrincipal Jwt jwt,@PathVariable Long jobId){
        return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff")
                .header("Referrer-Policy","no-referrer")
                .header("Content-Disposition","inline; filename=\"foodguard-inspection-"+jobId+".html\"")
                .body(reports.report(Long.valueOf(jwt.getSubject()),jobId));
    }

    // Public login shell only. No inspection data is embedded before authenticated API access.
    @GetMapping(value="/reports/{jobId}",produces="text/html;charset=UTF-8")
    public ResponseEntity<String> page(@PathVariable Long jobId) throws IOException {
        String nonce=UUID.randomUUID().toString();
        String template=new ClassPathResource("report/report.html").getContentAsString(StandardCharsets.UTF_8);
        // Blob previews inherit the launcher's CSP. Allow only these exact static report assets.
        String scriptHash=hashElement(template,"script"),styleHash=hashElement(template,"style");
        String html=new ClassPathResource("report/open.html").getContentAsString(StandardCharsets.UTF_8)
                .replace("__NONCE__",nonce).replace("__REPORT_ID__",jobId.toString());
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Referrer-Policy","no-referrer")
                .header("Content-Security-Policy","default-src 'none'; script-src 'nonce-"+nonce+"' "+scriptHash
                        +"; style-src 'nonce-"+nonce+"' "+styleHash+"; connect-src 'self'; frame-src 'self' blob:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
                .body(html);
    }
    private static String hashElement(String html,String tag){
        var matcher=Pattern.compile("<"+tag+"[^>]*>([\\s\\S]*?)</"+tag+">").matcher(html);
        if(!matcher.find())throw new IllegalStateException("Missing report asset");
        try{return "'sha256-"+Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(matcher.group(1).getBytes(StandardCharsets.UTF_8)))+"'";}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
