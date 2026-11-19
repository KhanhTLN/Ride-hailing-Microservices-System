package com.threecats.tripservice.repository;

import com.threecats.tripservice.entity.Trip;
import com.threecats.tripservice.entity.enums.TripStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TripRepository extends JpaRepository<Trip, UUID> {

    Optional<Trip> findByQuoteId(UUID quoteId);

    @Query("SELECT t FROM Trip t WHERE t.passengerId = :passengerId AND t.status NOT IN :terminalStatuses")
    Optional<Trip> findActiveTripByPassenger(
            @Param("passengerId") UUID passengerId,
            @Param("terminalStatuses") Collection<TripStatus> terminalStatuses
    );

    boolean existsByPassengerIdAndStatusNotIn(UUID passengerId, Collection<TripStatus> terminalStatuses);

    List<Trip> findByPassengerIdOrderByCreatedAtDesc(UUID passengerId, Pageable pageable);

    List<Trip> findByDriverUserIdOrderByCreatedAtDesc(UUID driverUserId, Pageable pageable);

    List<Trip> findByDriverIdOrderByCreatedAtDesc(UUID driverId, Pageable pageable);
}
