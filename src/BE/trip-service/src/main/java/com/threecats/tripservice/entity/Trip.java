package com.threecats.tripservice.entity;

import com.threecats.tripservice.entity.enums.PaymentStatus;
import com.threecats.tripservice.entity.enums.TripStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "trip", schema = "public")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Trip {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "passenger_id", nullable = false)
    private UUID passengerId;

    @Column(name = "driver_id")
    private UUID driverId;

    @Column(name = "driver_user_id")
    private UUID driverUserId;

    @Column(name = "quote_id", nullable = false, unique = true)
    private UUID quoteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TripStatus status;

    @Column(name = "current_attempt_id", nullable = false)
    private UUID currentAttemptId;

    @Column(name = "search_budget_seconds", nullable = false)
    @Builder.Default
    private Integer searchBudgetSeconds = 120;

    @Column(name = "pickup_latitude", nullable = false)
    private Double pickupLatitude;

    @Column(name = "pickup_longitude", nullable = false)
    private Double pickupLongitude;

    @Column(name = "pickup_address", length = 255)
    private String pickupAddress;

    @Column(name = "dropoff_latitude", nullable = false)
    private Double dropoffLatitude;

    @Column(name = "dropoff_longitude", nullable = false)
    private Double dropoffLongitude;

    @Column(name = "dropoff_address", length = 255)
    private String dropoffAddress;

    @Column(name = "vehicle_type", nullable = false, length = 20)
    private String vehicleType;

    @Column(name = "fare_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal fareAmount;

    @Column(name = "fare_currency", nullable = false, length = 10)
    @Builder.Default
    private String fareCurrency = "VND";

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "attempt_number", nullable = false)
    @Builder.Default
    private Integer attemptNumber = 1;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
