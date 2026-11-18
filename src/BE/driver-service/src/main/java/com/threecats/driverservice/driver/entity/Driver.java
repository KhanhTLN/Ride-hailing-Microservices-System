package com.threecats.driverservice.driver.entity;

import com.threecats.driverservice.enums.DriverActivityStatus;
import com.threecats.driverservice.enums.DriverApprovalStatus;
import com.threecats.driverservice.enums.VehicleType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "driver")
public class Driver {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "driver_id")
    private UUID driverId;

    @Column(name = "license_number", nullable = false, unique = true, length = 50)
    private String licenseNumber;

    @Column(name = "identity_card", length = 20)
    private String identityCard;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    @Builder.Default
    private DriverApprovalStatus approvalStatus = DriverApprovalStatus.PENDING;

    @Column(name = "accepting_trips")
    @Builder.Default
    private Boolean acceptingTrips = false;

    // Legacy vehicle fields
    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", length = 20)
    @Builder.Default
    private VehicleType vehicleType = VehicleType.BIKE;

    @Column(name = "vehicle_plate", length = 20)
    private String vehiclePlate;

    @Column(name = "vehicle_model", length = 50)
    private String vehicleModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_status", length = 20)
    @Builder.Default
    private DriverActivityStatus activityStatus = DriverActivityStatus.OFFLINE;

    // Simulated Location
    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "last_location_updated_at")
    private OffsetDateTime lastLocationUpdatedAt;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "current_session_id")
    private UUID currentSessionId;

    @Column(name = "current_sequence_number")
    private Long currentSequenceNumber;

    // Optimistic Concurrency Control
    @Version
    @Column(name = "version")
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return driverId != null ? driverId : userId;
    }

    public void setId(UUID id) {
        this.driverId = id;
    }
}
