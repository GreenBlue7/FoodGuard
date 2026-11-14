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

    @Autowired
    public ProductRefreshService(ProductApiService importer, ProductSyncStateRepository states) {
        this(importer, states, Clock.system(SEOUL));
    }

    ProductRefreshService(ProductApiService importer, ProductSyncStateRepository states, Clock clock) {
        this.importer = importer;
        this.states = states;
        this.clock = clock;
    }

    // Single scheduler instance. No DB transaction is held during the external API calls.
    public synchronized String refresh() {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        ProductSyncState previous = states.findById("I0030").orElse(null);
        // Re-read one extra day: date boundaries and delayed publication must not lose changes.
        LocalDate since = previous == null ? null : previous.getCoveredThrough().minusDays(1);
        // The API documents a seven-day incremental window. Outside it, recover with a full import.
        if (since != null && (since.isBefore(today.minusDays(7)) || !since.isBefore(today))) since = null;
        ProductApiService.ImportOutcome outcome = importer.importSince(since);
        String mode = since == null ? "전체 복구" : "증분(" + since + " 이후)";
        if (!outcome.completed()) return mode + ": " + outcome.message() + " [기준일 유지]";
        // Today's updates may not be published until 19:00. Never claim today is fully covered.
        states.saveAndFlush(new ProductSyncState("I0030", today.minusDays(1)));
        return mode + ": " + outcome.message();
    }
}
