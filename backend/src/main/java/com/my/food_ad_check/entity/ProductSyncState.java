package com.my.food_ad_check.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

/** A conservative date watermark, advanced only after all pages are durably imported. */
@Entity
@Table(name = "product_sync_state")
public class ProductSyncState {
    @Id @Column(name = "service_id", length = 20)
    private String serviceId;
    @Column(name = "covered_through", nullable = false)
    private LocalDate coveredThrough;
    @Column(name = "last_full_sync_on")
    private LocalDate lastFullSyncOn;

    protected ProductSyncState() { }
    public ProductSyncState(String serviceId, LocalDate coveredThrough) {
        this(serviceId, coveredThrough, null);
    }
    public ProductSyncState(String serviceId, LocalDate coveredThrough, LocalDate lastFullSyncOn) {
        this.serviceId = serviceId;
        this.coveredThrough = coveredThrough;
        this.lastFullSyncOn = lastFullSyncOn;
    }
    public String getServiceId() { return serviceId; }
    public LocalDate getCoveredThrough() { return coveredThrough; }
    public LocalDate getLastFullSyncOn() { return lastFullSyncOn; }
}
