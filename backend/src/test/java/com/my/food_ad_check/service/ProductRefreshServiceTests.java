package com.my.food_ad_check.service;

import com.my.food_ad_check.entity.ProductSyncState;
import com.my.food_ad_check.repository.ProductSyncStateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductRefreshServiceTests {
    private final ProductApiService importer = mock(ProductApiService.class);
    private final ProductSyncStateRepository states = mock(ProductSyncStateRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T18:00:00Z"), ZoneId.of("UTC")); // Seoul Nov 11, 03:00
    private final ProductRefreshService service = new ProductRefreshService(importer, states, clock);

    @Test
    void firstRunUsesFullImportAndPersistsOnlyYesterdayInSeoul() {
        when(states.findById("I0030")).thenReturn(Optional.empty());
        when(importer.importSince(null)).thenReturn(new ProductApiService.ImportOutcome(true, "ok"));
        assertTrue(service.refresh().startsWith("전체 복구"));
        var state = ArgumentCaptor.forClass(ProductSyncState.class);
        verify(states).saveAndFlush(state.capture());
        assertEquals(LocalDate.of(2026, 11, 10), state.getValue().getCoveredThrough());
        assertEquals(LocalDate.of(2026, 11, 11), state.getValue().getLastFullSyncOn());
    }

    @Test
    void restartUsesStoredDateWithOneDayOverlap() {
        when(states.findById("I0030")).thenReturn(Optional.of(new ProductSyncState("I0030", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 5))));
        when(importer.importSince(LocalDate.of(2026, 11, 8))).thenReturn(new ProductApiService.ImportOutcome(true, "ok"));
        new ProductRefreshService(importer, states, clock).refresh();
        verify(importer).importSince(LocalDate.of(2026, 11, 8));
        verify(states).saveAndFlush(any());
    }

    @Test
    void failureOrBusyNeverAdvancesWatermark() {
        when(states.findById("I0030")).thenReturn(Optional.of(new ProductSyncState("I0030", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 5))));
        when(importer.importSince(any())).thenReturn(new ProductApiService.ImportOutcome(false, "failed"));
        assertTrue(service.refresh().endsWith("[기준일 유지]"));
        verify(states, never()).saveAndFlush(any());
    }

    @Test
    void gapBeyondSevenDaysFallsBackToFullImport() {
        when(states.findById("I0030")).thenReturn(Optional.of(new ProductSyncState("I0030", LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 5))));
        when(importer.importSince(null)).thenReturn(new ProductApiService.ImportOutcome(true, "ok"));
        service.refresh();
        verify(importer).importSince(null);
    }

    @Test
    void invalidFutureWatermarkFallsBackToFullImport() {
        when(states.findById("I0030")).thenReturn(Optional.of(new ProductSyncState("I0030", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 5))));
        when(importer.importSince(null)).thenReturn(new ProductApiService.ImportOutcome(true, "ok"));
        service.refresh();
        verify(importer).importSince(null);
    }

    @Test
    void failedCheckpointWriteDoesNotReportSuccessfulRefresh() {
        when(importer.importSince(null)).thenReturn(new ProductApiService.ImportOutcome(true, "ok"));
        doThrow(new IllegalStateException("DB unavailable")).when(states).saveAndFlush(any());
        assertThrows(IllegalStateException.class, service::refresh);
    }

    @Test
    void fullImportRunsExactlySevenDaysAfterLastSuccessfulFullImport() {
        when(states.findById("I0030")).thenReturn(Optional.of(new ProductSyncState("I0030",
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 4))));
        when(importer.importSince(null)).thenReturn(new ProductApiService.ImportOutcome(true, "ok"));
        service.refresh();
        verify(importer).importSince(null);
        var saved = ArgumentCaptor.forClass(ProductSyncState.class);
        verify(states).saveAndFlush(saved.capture());
        assertEquals(LocalDate.of(2026, 11, 11), saved.getValue().getLastFullSyncOn());
    }

    @Test
    void daySixUsesIncrementalAndPreservesFullImportDateAcrossRestart() {
        when(states.findById("I0030")).thenReturn(Optional.of(new ProductSyncState("I0030",
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 5))));
        when(importer.importSince(LocalDate.of(2026, 11, 9)))
                .thenReturn(new ProductApiService.ImportOutcome(true, "ok"));
        new ProductRefreshService(importer, states, clock).refresh();
        verify(importer).importSince(LocalDate.of(2026, 11, 9));
        var saved = ArgumentCaptor.forClass(ProductSyncState.class);
        verify(states).saveAndFlush(saved.capture());
        assertEquals(LocalDate.of(2026, 11, 5), saved.getValue().getLastFullSyncOn());
    }

    @Test
    void unknownLegacyFullImportDateForcesFullImport() {
        when(states.findById("I0030")).thenReturn(Optional.of(
                new ProductSyncState("I0030", LocalDate.of(2026, 11, 10))));
        when(importer.importSince(null)).thenReturn(new ProductApiService.ImportOutcome(true, "ok"));
        service.refresh();
        verify(importer).importSince(null);
    }

    @Test
    void failedWeeklyFullImportDoesNotPostponeRetry() {
        when(states.findById("I0030")).thenReturn(Optional.of(new ProductSyncState("I0030",
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 4))));
        when(importer.importSince(null)).thenReturn(new ProductApiService.ImportOutcome(false, "failed"));
        service.refresh();
        new ProductRefreshService(importer, states, clock).refresh();
        verify(importer, times(2)).importSince(null);
        verify(states, never()).saveAndFlush(any());
    }

    @Test
    void corruptedFutureFullImportDateForcesFullImport() {
        when(states.findById("I0030")).thenReturn(Optional.of(new ProductSyncState("I0030",
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12))));
        when(importer.importSince(null)).thenReturn(new ProductApiService.ImportOutcome(true, "ok"));
        service.refresh();
        verify(importer).importSince(null);
    }
}
