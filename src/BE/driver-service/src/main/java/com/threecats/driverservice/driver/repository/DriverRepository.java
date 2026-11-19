package com.threecats.driverservice.driver.repository;

import com.threecats.driverservice.driver.entity.Driver;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DriverRepository extends JpaRepository<Driver, UUID> {
    boolean existsByLicenseNumber(String licenseNumber);
    boolean existsByIdentityCard(String identityCard);
    boolean existsByVehiclePlate(String vehiclePlate);
    Optional<Driver> findByLicenseNumber(String licenseNumber);
    Optional<Driver> findByDriverId(UUID driverId);
    Optional<Driver> findByUserId(UUID userId);
    List<Driver> findByApprovalStatusAndAcceptingTripsTrue(com.threecats.driverservice.enums.DriverApprovalStatus approvalStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Driver d where d.userId = :userId")
    Optional<Driver> lockByUserId(@Param("userId") UUID userId);

    default Optional<Driver> findByIdOrUserId(UUID id) {
        return findById(id).or(() -> findByDriverId(id));
    }
}
