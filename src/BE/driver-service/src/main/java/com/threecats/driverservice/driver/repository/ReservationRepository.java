package com.threecats.driverservice.driver.repository;

import com.threecats.driverservice.driver.entity.Reservation;
import com.threecats.driverservice.enums.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT r FROM Reservation r WHERE r.driverId = :driverId AND r.status IN :statuses")
    List<Reservation> findByDriverIdAndStatusIn(@Param("driverId") UUID driverId, @Param("statuses") List<ReservationStatus> statuses);

    @Query("SELECT r FROM Reservation r WHERE r.tripId = :tripId AND r.status IN :statuses")
    List<Reservation> findByTripIdAndStatusIn(@Param("tripId") UUID tripId, @Param("statuses") List<ReservationStatus> statuses);

    Optional<Reservation> findByIdAndTripId(UUID id, UUID tripId);

    List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, OffsetDateTime time);
}
