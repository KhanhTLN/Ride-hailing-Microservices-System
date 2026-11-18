package com.threecats.driverservice.driver.service;

import com.threecats.common.exception.BaseException;
import com.threecats.common.exception.validationAndRequest.InvalidInputException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class DriverReservationService {
    private static final RowMapper<Reservation> ROW = (row, index) -> new Reservation(
            row.getObject("id", UUID.class), row.getObject("driver_id", UUID.class),
            row.getObject("trip_id", UUID.class), row.getString("owner_service"),
            Status.valueOf(row.getString("status")), row.getObject("expires_at", OffsetDateTime.class),
            row.getObject("updated_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;
    private final DriverIdempotencyService idempotency;

    public DriverReservationService(JdbcTemplate jdbc, DriverIdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
    }

    public Reservation reserve(String caller, String key, UUID driverId, UUID tripId, int seconds) {
        if (!"matching-service".equals(caller)) {
            throw forbidden();
        }
        if (seconds < 15 || seconds > 60) {
            throw new InvalidInputException("hold_seconds must be between 15 and 60");
        }
        return idempotency.execute(caller, "ReserveDriver", key, new Hold(driverId, tripId, seconds),
                Reservation.class, () -> {
                    lockDriver(driverId);
                    expireDriverHolds(driverId);
                    Boolean eligible = jdbc.queryForObject("""
                            SELECT approval_status = 'APPROVED' AND accepting_trips
                                AND last_seen_at >= statement_timestamp() - interval '30 seconds'
                                AND last_location_updated_at >= statement_timestamp() - interval '30 seconds'
                                AND latitude IS NOT NULL AND longitude IS NOT NULL
                                AND EXISTS (SELECT 1 FROM public.vehicle v
                                            WHERE v.driver_id = d.driver_id AND v.is_active)
                            FROM public.driver d WHERE driver_id = ?
                            """, Boolean.class, driverId);
                    if (!Boolean.TRUE.equals(eligible)) {
                        throw unavailable();
                    }
                    return jdbc.query("""
                            INSERT INTO public.driver_reservation (driver_id, trip_id, owner_service, status, expires_at)
                            VALUES (?, ?, ?, 'HELD', statement_timestamp() + (? * interval '1 second'))
                            ON CONFLICT DO NOTHING RETURNING *
                            """, ROW, driverId, tripId, caller, seconds).stream()
                            .findFirst().orElseThrow(DriverReservationService::unavailable);
                });
    }

    public Reservation confirm(String caller, String key, UUID reservationId, UUID tripId) {
        if (!"trip-service".equals(caller)) {
            throw forbidden();
        }
        return idempotency.execute(caller, "ConfirmReservation", key, new Change(reservationId, tripId, null),
                Reservation.class, () -> {
                    Reservation current = lockReservation(reservationId, tripId);
                    if (current.status() == Status.CONFIRMED) {
                        return current;
                    }
                    return jdbc.query("""
                            UPDATE public.driver_reservation
                            SET status = 'CONFIRMED', updated_at = statement_timestamp()
                            WHERE id = ? AND status = 'HELD' AND expires_at > statement_timestamp()
                            RETURNING *
                            """, ROW, reservationId).stream().findFirst().orElseThrow(() ->
                            new ReservationFailure(409, "RESERVATION_EXPIRED", "Reservation can no longer be confirmed"));
                });
    }

    public Reservation release(String caller, String key, UUID reservationId, UUID tripId, String reason) {
        boolean matching = "matching-service".equals(caller);
        if (!matching && !"trip-service".equals(caller)) {
            throw forbidden();
        }
        return idempotency.execute(caller, "ReleaseReservation", key, new Change(reservationId, tripId, reason),
                Reservation.class, () -> {
                    Reservation current = lockReservation(reservationId, tripId);
                    if (matching && (!caller.equals(current.owner()) || current.status() == Status.CONFIRMED)) {
                        throw forbidden();
                    }
                    if (current.status() == Status.RELEASED || current.status() == Status.EXPIRED) {
                        return current;
                    }
                    return jdbc.queryForObject("""
                            UPDATE public.driver_reservation
                            SET status = CASE WHEN status = 'HELD' AND expires_at <= statement_timestamp()
                                              THEN 'EXPIRED' ELSE 'RELEASED' END,
                                updated_at = statement_timestamp()
                            WHERE id = ? RETURNING *
                            """, ROW, reservationId);
                });
    }

    public Reservation get(UUID id) {
        Reservation current = jdbc.query("SELECT * FROM public.driver_reservation WHERE id = ?", ROW, id)
                .stream().findFirst().orElseThrow(() -> new ReservationFailure(404, "NOT_FOUND", "Reservation not found"));
        if (current.status() == Status.HELD && !current.expiresAt().isAfter(OffsetDateTime.now())) {
            return new Reservation(current.id(), current.driverId(), current.tripId(), current.owner(),
                    Status.EXPIRED, current.expiresAt(), current.updatedAt());
        }
        return current;
    }

    public void ensureVehicleCanChange(UUID driverId) {
        expireDriverHolds(driverId);
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.driver_reservation
                               WHERE driver_id = ? AND status IN ('HELD', 'CONFIRMED'))
                """, Boolean.class, driverId);
        if (Boolean.TRUE.equals(active)) {
            throw unavailable();
        }
    }

    @Scheduled(fixedDelayString = "${driver.reservation.expiry-delay-ms:1000}")
    public void expireHolds() {
        jdbc.update("""
                UPDATE public.driver_reservation
                SET status = 'EXPIRED', updated_at = statement_timestamp()
                WHERE id IN (
                    SELECT id FROM public.driver_reservation
                    WHERE status = 'HELD' AND expires_at <= statement_timestamp()
                    ORDER BY expires_at, id LIMIT 100 FOR UPDATE SKIP LOCKED
                )
                """);
    }

    private void expireDriverHolds(UUID driverId) {
        jdbc.update("""
                UPDATE public.driver_reservation
                SET status = 'EXPIRED', updated_at = statement_timestamp()
                WHERE driver_id = ? AND status = 'HELD' AND expires_at <= statement_timestamp()
                """, driverId);
    }

    private Reservation lockReservation(UUID id, UUID tripId) {
        Reservation current = get(id);
        lockDriver(current.driverId());
        Reservation locked = jdbc.queryForObject(
                "SELECT * FROM public.driver_reservation WHERE id = ? FOR UPDATE", ROW, id);
        if (!tripId.equals(locked.tripId())) {
            throw new ReservationFailure(409, "RESERVATION_MISMATCH", "Reservation belongs to another trip");
        }
        return locked;
    }

    private void lockDriver(UUID id) {
        List<UUID> locked = jdbc.query("SELECT driver_id FROM public.driver WHERE driver_id = ? FOR UPDATE",
                (row, index) -> row.getObject(1, UUID.class), id);
        if (locked.isEmpty()) {
            throw new ReservationFailure(404, "NOT_FOUND", "Driver not found");
        }
    }

    private static ReservationFailure unavailable() {
        return new ReservationFailure(409, "DRIVER_UNAVAILABLE", "Driver is not available");
    }

    private static ReservationFailure forbidden() {
        return new ReservationFailure(403, "FORBIDDEN", "Caller cannot perform this reservation operation");
    }

    public enum Status { HELD, CONFIRMED, RELEASED, EXPIRED }

    public record Reservation(UUID id, UUID driverId, UUID tripId, String owner,
                              Status status, OffsetDateTime expiresAt, OffsetDateTime updatedAt) {}

    private record Hold(UUID driverId, UUID tripId, int seconds) {}

    private record Change(UUID reservationId, UUID tripId, String reason) {}

    private static final class ReservationFailure extends BaseException {
        private ReservationFailure(int status, String code, String message) {
            super(status, code, message);
        }
    }
}
