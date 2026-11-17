package com.threecats.driverservice.driver.repository;

import com.threecats.driverservice.driver.entity.Driver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DriverRepository extends JpaRepository<Driver, UUID> {
    boolean existsByLicenseNumber(String licenseNumber);
    boolean existsByIdentityCard(String identityCard);
    boolean existsByVehiclePlate(String vehiclePlate);
    Optional<Driver> findByLicenseNumber(String licenseNumber);
}
