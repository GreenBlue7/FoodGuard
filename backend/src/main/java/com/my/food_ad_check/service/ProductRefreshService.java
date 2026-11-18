package com.my.food_ad_check.service;

import com.my.food_ad_check.entity.ProductSyncState;
import com.my.food_ad_check.repository.ProductSyncStateRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProductRefreshService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final ProductApiService importer;
    private final ProductSyncStateRepository states;
    private final Clock clock;
    private final ProductSyncRunTracker runs;

    @Autowired
    public ProductRefreshService(ProductApiService importer, ProductSyncStateRepository states, ProductSyncRunTracker runs) {
        this(importer, states, Clock.system(SEOUL), runs);
    }

    ProductRefreshService(ProductApiService importer, ProductSyncStateRepository states, Clock clock, ProductSyncRunTracker runs) {
        this.importer = importer;
        this.states = states;
        this.clock = clock;
        this.runs = runs;
    }

    // Single scheduler instance. No DB transaction is held during the external API calls.
    public synchronized String refresh() {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        ProductSyncState previous = states.findById("I0030").orElse(null);
        // Re-read one extra day: date boundaries and delayed publication must not lose changes.
        LocalDate since = previous == null ? null : previous.getCoveredThrough().minusDays(1);
        LocalDate lastFull = previous == null ? null : previous.getLastFullSyncOn();
        // Persist successes only: restart cannot reset the weekly deadline; failed full imports retry.
        if (lastFull == null || lastFull.isAfter(today) || !lastFull.plusDays(7).isAfter(today)) since = null;
        // The API documents a seven-day incremental window. Outside it, recover with a full import.
        if (since != null && (since.isBefore(today.minusDays(7)) || !since.isBefore(today))) since = null;
        long runId = runs.start(since == null ? "FULL" : "INCREMENTAL");
        ProductApiService.ImportOutcome outcome;
        try { outcome = importer.importSince(since); }
        catch (RuntimeException failure) {
            runs.failWithoutMasking(runId, "IMPORT_EXCEPTION");
            throw failure;
        }
        String mode = since == null ? "전체 복구" : "증분(" + since + " 이후)";
        if (!outcome.completed()) {
            runs.finish(runId, "FAILED", "IMPORT_NOT_COMPLETED");
            return mode + ": " + outcome.message() + " [기준일 유지]";
        }
        // Today's updates may not be published until 19:00. Never claim today is fully covered.
        try { states.saveAndFlush(new ProductSyncState("I0030", today.minusDays(1), since == null ? today : lastFull)); }
        catch (RuntimeException failure) {
            runs.failWithoutMasking(runId, "CHECKPOINT_FAILED");
            throw failure;
        }
        runs.finish(runId, "SUCCEEDED", null);
        return mode + ": " + outcome.message();
    }
}
