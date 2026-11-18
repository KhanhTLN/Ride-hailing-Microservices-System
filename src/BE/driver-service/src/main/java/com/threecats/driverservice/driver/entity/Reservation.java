package com.threecats.driverservice.driver.entity;

import com.threecats.driverservice.enums.ReservationStatus;
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
@Table(name = "reservation", indexes = {
        @Index(name = "idx_reservation_driver_status", columnList = "driver_id, status"),
        @Index(name = "idx_reservation_trip_status", columnList = "trip_id, status"),
        @Index(name = "idx_reservation_idempotency", columnList = "idempotency_key")
})
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "hold_seconds")
    private Integer holdSeconds;

    @Column(name = "caller", length = 50)
    private String caller;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public boolean isExpired() {
        return status == ReservationStatus.HELD && expiresAt != null && OffsetDateTime.now().isAfter(expiresAt);
    }
}
