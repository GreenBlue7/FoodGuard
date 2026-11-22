package com.my.food_ad_check.controller;

import com.my.food_ad_check.entity.*;
import com.my.food_ad_check.repository.*;
import com.my.food_ad_check.service.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"notification-email.delivery-enabled=true","notification-email.poll-ms=3600000"})
@AutoConfigureMockMvc
class NotificationEmailTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired InspectionRecordRepository records;
    @Autowired ProductFunctionChangeRepository changes;
    @Autowired ProductChangeNotificationRepository notifications;
    @Autowired ProductImportWriter writer;
    @Autowired NotificationEmailQueue queue;
    @Autowired NotificationEmailPreferences preferences;
    @MockitoBean JavaMailSender sender;
    @MockitoBean AiJudgmentService judge;
    private final List<Long> userIds=new ArrayList<>();
    private final List<String> reports=new ArrayList<>();

    @AfterEach void cleanup() {
        for(long id:userIds) db.update("DELETE FROM users WHERE id=?",id);
        for(String report:reports) {
            db.update("DELETE FROM product_function_changes WHERE prdlst_report_no=?",report);
            db.update("DELETE FROM product_approved_functions WHERE prdlst_report_no=?",report);
            db.update("DELETE FROM products WHERE prdlst_report_no=?",report);
        }
    }
    private User user() {
        var u=users.saveAndFlush(new User("mail-"+UUID.randomUUID()+"@test.example","fixture",null));
        userIds.add(u.getId());return u;
    }
    private void optIn(User u) {
        db.update("INSERT INTO notification_email_preferences(user_id,enabled,verified_email,verified_at) VALUES (?,true,?,localtimestamp)",u.getId(),u.getEmail());
    }
    private long change(User user, User alsoInspected) {
        Product product=new Product();product.setPrdlstReportNo("mail-"+UUID.randomUUID());reports.add(product.getPrdlstReportNo());
        product.setPrdlstNm("[테스트] 제품");product.setProductType("건강기능식품");product.setPrimaryFnclty("새 기능성");
        product=products.saveAndFlush(product);
        records.saveAndFlush(new InspectionRecord(user.getId(),product.getPrdlstReportNo(),product.getPrdlstNm(),null,
                "건강기능식품","옛 기능성","[테스트] 광고","DONE","{}",LocalDateTime.now()));
        if(alsoInspected!=null)records.saveAndFlush(new InspectionRecord(alsoInspected.getId(),product.getPrdlstReportNo(),product.getPrdlstNm(),null,
                "건강기능식품","옛 기능성","[테스트] 광고","DONE","{}",LocalDateTime.now()));
        writer.savePage(List.of(product),List.of(new ProductFunctionChange(product.getPrdlstReportNo(),"옛 기능성","새 기능성",LocalDateTime.now())));
        return db.queryForObject("SELECT n.id FROM product_change_notifications n JOIN product_function_changes c ON c.id=n.change_id WHERE n.user_id=? AND c.prdlst_report_no=?",Long.class,user.getId(),product.getPrdlstReportNo());
    }
    private void ready() { db.update("UPDATE notification_email_outbox SET available_at=localtimestamp-interval '1 second' WHERE user_id IN ("+String.join(",",userIds.stream().map(Object::toString).toList())+")"); }
    private String token(SimpleMailMessage message) {
        var matcher=Pattern.compile("#token=([A-Za-z0-9_-]{43})").matcher(message.getText());
        assertTrue(matcher.find());return matcher.group(1);
    }

    @Test void verificationIsExplicitSingleUseAndOptInRequiresVerifiedAddress() throws Exception {
        var user=user();var auth=jwt().jwt(j->j.subject(user.getId().toString()));
        mvc.perform(get("/api/notification-email")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notification-email").with(auth)).andExpect(jsonPath("$.verified").value(false)).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(put("/api/notification-email").with(auth).contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/notification-email").with(auth).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/notification-email/verification").with(auth)).andExpect(status().isAccepted());
        var captured=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(sender).send(captured.capture());
        assertArrayEquals(new String[]{user.getEmail()},captured.getValue().getTo());
        String token=token(captured.getValue());
        assertNotEquals(token,db.queryForObject("SELECT token_hash FROM notification_email_preferences WHERE user_id=?",String.class,user.getId()));
        mvc.perform(get("/email/verify")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        assertFalse(preferences.get(user.getId()).verified());
        mvc.perform(post("/api/notification-email/verification").with(auth)).andExpect(status().isTooManyRequests());
        mvc.perform(post("/email/verify").param("token",token)).andExpect(status().isOk());
        assertTrue(preferences.get(user.getId()).verified());assertFalse(preferences.get(user.getId()).enabled());
        mvc.perform(post("/email/verify").param("token",token)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/notification-email").with(auth).contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        verifyNoInteractions(judge);
    }

    @Test void expiredAndReplacedTokensDoNotVerifyAnAddress() throws Exception {
        var user=user();preferences.requestVerification(user.getId());
        var mails=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(sender).send(mails.capture());String old=token(mails.getValue());
        db.update("UPDATE notification_email_preferences SET token_expires_at=localtimestamp-interval '1 second',verification_last_sent_at=localtimestamp-interval '2 minutes' WHERE user_id=?",user.getId());
        mvc.perform(post("/email/verify").param("token",old)).andExpect(status().isBadRequest());
        preferences.requestVerification(user.getId());verify(sender,times(2)).send(mails.capture());String current=token(mails.getValue());
        assertNotEquals(old,current);
        mvc.perform(post("/email/verify").param("token",old)).andExpect(status().isBadRequest());
        mvc.perform(post("/email/verify").param("token",current)).andExpect(status().isOk());
    }

    @Test void verificationSendFailureRollsBackThrottleAndToken() {
        var user=user();doThrow(new MailSendException("failure")).when(sender).send(any(SimpleMailMessage.class));
        assertThrows(MailSendException.class,()->preferences.requestVerification(user.getId()));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM notification_email_preferences WHERE user_id=?",Integer.class,user.getId()));
        reset(sender);preferences.requestVerification(user.getId());verify(sender).send(any(SimpleMailMessage.class));
    }

    @Test void onlyVerifiedSubscribersAreQueuedAndDigestIsSentOnceWithoutAi() throws Exception {
        var owner=user();var other=user();optIn(owner);
        long first=change(owner,other);long second=change(owner,null);
        queue.enqueue(first);queue.enqueue(first);
        assertEquals(2,db.queryForObject("SELECT count(*) FROM notification_email_outbox WHERE user_id=?",Integer.class,owner.getId()));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM notification_email_outbox WHERE user_id=?",Integer.class,other.getId()));
        assertEquals(0,queue.dispatch());verifyNoInteractions(sender);
        ready();assertEquals(1,queue.dispatch());assertEquals(0,queue.dispatch());
        var mails=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(sender).send(mails.capture());
        assertTrue(mails.getValue().getText().contains("/email/notifications/"+first));
        assertTrue(mails.getValue().getText().contains("/email/notifications/"+second));
        assertTrue(mails.getValue().getText().contains("위반이 확정되었다는 뜻이 아닙니다"));
        assertFalse(mails.getValue().getText().contains("[테스트] 광고"));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM notification_email_outbox WHERE user_id=? AND state='SENT'",Integer.class,owner.getId()));
        mvc.perform(get("/email/notifications/"+first)).andExpect(status().isOk());
        mvc.perform(get("/api/notifications/"+first+"/ad-context").with(jwt().jwt(j->j.subject(other.getId().toString())))).andExpect(status().isNotFound());
        verifyNoInteractions(judge);
    }

    @Test void optOutCancelsPendingMailAndOldNotificationsAreNotBackfilled() {
        var owner=user();long old=change(owner,null);optIn(owner);assertEquals(0,queue.dispatch());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM notification_email_outbox WHERE notification_id=?",Integer.class,old));
        change(owner,null);preferences.setEnabled(owner.getId(),false);ready();assertEquals(0,queue.dispatch());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM notification_email_outbox WHERE user_id=? AND state='SKIPPED'",Integer.class,owner.getId()));
        verifyNoInteractions(sender);
    }

    @Test void smtpFailureRetriesWithBackoffAndStopsAfterFiveAttempts() {
        var owner=user();optIn(owner);long id=change(owner,null);ready();
        doThrow(new MailSendException("secret failure details")).when(sender).send(any(SimpleMailMessage.class));
        assertEquals(0,queue.dispatch());assertEquals("PENDING",db.queryForObject("SELECT state FROM notification_email_outbox WHERE notification_id=?",String.class,id));
        assertEquals("SMTP_SEND_FAILED",db.queryForObject("SELECT last_error FROM notification_email_outbox WHERE notification_id=?",String.class,id));
        assertEquals(1,db.queryForObject("SELECT attempts FROM notification_email_outbox WHERE notification_id=?",Integer.class,id));
        assertEquals(0,queue.dispatch());verify(sender,times(1)).send(any(SimpleMailMessage.class));
        db.update("UPDATE notification_email_outbox SET attempts=4 WHERE notification_id=?",id);ready();queue.dispatch();
        assertEquals("FAILED",db.queryForObject("SELECT state FROM notification_email_outbox WHERE notification_id=?",String.class,id));
        ready();queue.dispatch();verify(sender,times(2)).send(any(SimpleMailMessage.class));
    }

    @Test void expiredProcessingLeaseRecoversButUnverifiedChangedAddressIsSkipped() {
        var owner=user();optIn(owner);long id=change(owner,null);
        db.update("UPDATE notification_email_outbox SET state='PROCESSING',attempts=1,lease_until=localtimestamp-interval '1 minute' WHERE notification_id=?",id);
        ready();assertEquals(1,queue.dispatch());
        long next=change(owner,null);db.update("UPDATE users SET email=? WHERE id=?","changed-"+UUID.randomUUID()+"@test.example",owner.getId());
        ready();assertEquals(0,queue.dispatch());
        assertEquals("SKIPPED",db.queryForObject("SELECT state FROM notification_email_outbox WHERE notification_id=?",String.class,next));
        verify(sender,times(1)).send(any(SimpleMailMessage.class));
    }
}
