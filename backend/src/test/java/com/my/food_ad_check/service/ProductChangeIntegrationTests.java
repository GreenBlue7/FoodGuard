package com.my.food_ad_check.service;

import com.my.food_ad_check.entity.*;
import com.my.food_ad_check.repository.*;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class ProductChangeIntegrationTests {
    @Autowired ProductRepository products;
    @Autowired ProductFunctionChangeRepository changes;
    @Autowired ProductChangeNotificationRepository notifications;
    @Autowired UserRepository users;
    @Autowired InspectionRecordRepository inspections;
    @Autowired ProductImportWriter writer;
    @Autowired ProductNotificationService notificationService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ProductSyncStateRepository syncStates;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void successfulRefreshWatermarkSurvivesServiceRestart() {
        var api = mock(ProductApiService.class);
        when(api.importSince(any())).thenReturn(new ProductApiService.ImportOutcome(true, "ok"));
        var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-11-10T18:00:00Z"), java.time.ZoneOffset.UTC);
        new ProductRefreshService(api, syncStates, clock).refresh();
        assertEquals(java.time.LocalDate.of(2026, 11, 10), syncStates.findById("I0030").orElseThrow().getCoveredThrough());
        new ProductRefreshService(api, syncStates, clock).refresh();
        verify(api).importSince(java.time.LocalDate.of(2026, 11, 9));
    }

    private Product product(String id) {
        Product product = new Product();
        product.setPrdlstReportNo(id);
        product.setPrdlstNm("테스트 제품");
        product.setProductType("건강기능식품");
        product.setPrimaryFnclty("이전 원문");
        product.setApprovedFunctions(List.of("검수된 기능"));
        return product;
    }

    @Test
    void concurrentImportCannotOverwriteNewerFunctionsOrCreateFalseAudit() {
        String id = "version-" + UUID.randomUUID();
        products.saveAndFlush(product(id));
        var stale = products.findById(id).orElseThrow();
        var newer = products.findById(id).orElseThrow();
        newer.setPrimaryFnclty("최신 기능성");
        newer.setApprovedFunctions(List.of("최신 기능"));
        products.saveAndFlush(newer);
        stale.setPrimaryFnclty("늦게 도착한 원문");
        assertThrows(RuntimeException.class, () -> writer.savePage(List.of(stale), List.of(
                new ProductFunctionChange(id, "이전 원문", "늦게 도착한 원문", LocalDateTime.now()))));
        assertEquals("최신 기능성", products.findById(id).orElseThrow().getPrimaryFnclty());
        assertTrue(changes.findFirstByReportNoAndDetectedAtAfterOrderByDetectedAtDescIdDesc(
                id, LocalDateTime.now().minusDays(1)).isEmpty());
    }

    @Test
    void realDatabaseImportDeduplicatesRecipientsAndUnchangedRefreshes() {
        String id = "test-" + UUID.randomUUID();
        var product = products.saveAndFlush(product(id));
        var owner = users.saveAndFlush(new User(id + "@test.example", "fixture", null));
        var other = users.saveAndFlush(new User(id + "other@test.example", "fixture", null));
        for (int i = 0; i < 2; i++) inspections.saveAndFlush(new InspectionRecord(owner.getId(), id,
                "테스트 제품", null, "건강기능식품", "이전 원문", "광고", "DONE", "{}", LocalDateTime.now()));
        var api = mock(RestTemplate.class);
        when(api.getForObject(any(URI.class), eq(String.class))).thenReturn("""
                {"I0030":{"total_count":"1","row":[{"PRDLST_REPORT_NO":"%s",
                 "PRDLST_NM":"테스트 제품","PRIMARY_FNCLTY":"피로개선에 도움을 줄 수 있음"}]}}
                """.formatted(id));
        var importer = new ProductApiService(products, writer, api, "test", "https://example.test");
        assertTrue(importer.loadAllProductData().contains("목록 자동 갱신) 1건"));
        assertEquals(1, notifications.countByUserIdAndReadAtIsNull(owner.getId()));
        assertEquals(0, notifications.countByUserIdAndReadAtIsNull(other.getId()));
        assertTrue(importer.loadAllProductData().contains("목록 자동 갱신) 0건"));
        assertEquals(1, notifications.countByUserIdAndReadAtIsNull(owner.getId()));
        var stored = products.findById(id).orElseThrow();
        assertEquals(List.of("피로개선"), stored.getApprovedFunctions());
        assertTrue(!stored.isFunctionSourceReviewRequired());
        var inbox = notificationService.list(owner.getId(), 0, 20, true);
        assertEquals("이전 원문", inbox.items().get(0).previousSource());
        notificationService.markRead(owner.getId(), inbox.items().get(0).id());
        assertEquals(0, notificationService.list(owner.getId(), 0, 20, true).unreadCount());
        assertEquals(1, notificationService.list(owner.getId(), 0, 20, false).items().size());
    }

    @Test
    void failedAuditWriteRollsBackTheProductUpdate() {
        String id = "rollback-" + UUID.randomUUID();
        products.saveAndFlush(product(id));
        var changed = products.findById(id).orElseThrow();
        changed.setPrimaryFnclty("이 값은 롤백되어야 함");
        assertThrows(RuntimeException.class, () -> writer.savePage(List.of(changed), List.of(
                new ProductFunctionChange("missing-" + UUID.randomUUID(), "old", "new", LocalDateTime.now()))));
        assertEquals("이전 원문", products.findById(id).orElseThrow().getPrimaryFnclty());
    }

    @Test
    void previousInspectionLinkIsPersistedWithoutOverwritingOriginal() {
        String id = "link-" + UUID.randomUUID();
        var user = users.saveAndFlush(new User(id + "@test.example", "fixture", null));
        var before = inspections.saveAndFlush(new InspectionRecord(user.getId(), null, null, null, null,
                null, "이전 문구", "DONE", "{}", LocalDateTime.now()));
        var after = new InspectionRecord(user.getId(), null, null, null, null, null,
                "수정 문구", "DONE", "{}", LocalDateTime.now());
        after.linkToPreviousInspection(before.getId());
        var saved = inspections.saveAndFlush(after);
        assertEquals(before.getId(), inspections.findByIdAndUserId(saved.getId(), user.getId())
                .orElseThrow().getPreviousInspectionId());
        assertEquals("이전 문구", inspections.findByIdAndUserId(before.getId(), user.getId()).orElseThrow().getAdText());
    }
}
