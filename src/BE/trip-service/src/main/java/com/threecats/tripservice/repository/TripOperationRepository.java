package com.threecats.tripservice.repository;

import com.threecats.tripservice.entity.TripOperation;
import com.threecats.tripservice.entity.enums.OperationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TripOperationRepository extends JpaRepository<TripOperation, UUID> {

    Optional<TripOperation> findByIdempotencyKey(String idempotencyKey);

    List<TripOperation> findByTripIdAndStatus(UUID tripId, OperationStatus status);

    boolean existsByTripIdAndStatus(UUID tripId, OperationStatus status);
}
