package com.threecats.driverservice.driver.repository;

import com.threecats.driverservice.driver.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
    Optional<Vehicle> findByDriverIdAndIsActiveTrue(UUID driverId);
    List<Vehicle> findByDriverId(UUID driverId);
    boolean existsByPlateNumber(String plateNumber);
}
