package com.threecats.driverservice.driver.service;

import com.threecats.common.dto.response.Ack;
import com.threecats.common.exception.resource.ResourceAlreadyExistException;
import com.threecats.common.exception.resource.ResourceNotFoundException;
import com.threecats.common.exception.validationAndRequest.InvalidInputException;
import com.threecats.common.grpc.UserProtoResponse;
import com.threecats.driverservice.driver.dto.PointDto;
import com.threecats.driverservice.driver.dto.request.*;
import com.threecats.driverservice.driver.dto.response.*;
import com.threecats.driverservice.driver.entity.Driver;
import com.threecats.driverservice.driver.entity.Vehicle;
import com.threecats.driverservice.driver.repository.DriverRepository;
import com.threecats.driverservice.driver.repository.VehicleRepository;
import com.threecats.driverservice.enums.DriverActivityStatus;
import com.threecats.driverservice.enums.DriverApprovalStatus;
import com.threecats.driverservice.grpc.UserGrpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DriverServiceImpl implements DriverService {

    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final UserGrpcClient userGrpcClient;

    // =========================================================================
    // DS03 API CONTRACT v1.0.0 METHODS
    // =========================================================================

    @Override
    @Transactional
    public DriverResponse createDriver(UUID userId, DriverCreate request) {
        log.info("Creating driver profile for userId: {}, license: {}", userId, request.getLicenseNumber());

        if (driverRepository.findByUserId(userId).isPresent()) {
            throw new ResourceAlreadyExistException("Driver profile already exists for this user");
        }
        if (driverRepository.existsByLicenseNumber(request.getLicenseNumber())) {
            throw new ResourceAlreadyExistException("License number already registered");
        }

        Driver driver = Driver.builder()
                .driverId(UUID.randomUUID())
                .userId(userId)
                .licenseNumber(request.getLicenseNumber().trim())
                .approvalStatus(DriverApprovalStatus.PENDING)
                .acceptingTrips(false)
                .activityStatus(DriverActivityStatus.OFFLINE)
                .createdAt(OffsetDateTime.now())
                .build();

        driver = driverRepository.save(driver);
        return mapToDriverResponse(driver);
    }

    @Override
    public DriverResponse getMyDriver(UUID userId) {
        Driver driver = driverRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found for user: " + userId));

        return mapToDriverResponse(driver);
    }

    @Override
    @Transactional
    public DriverResponse approveDriver(UUID driverId, ApprovalRequest request) {
        log.info("Approving driverId: {} with status: {}, reason: {}", driverId, request.getAccountStatus(), request.getReason());

        Driver driver = driverRepository.findByIdOrUserId(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found with id: " + driverId));

        DriverApprovalStatus newStatus;
        try {
            newStatus = DriverApprovalStatus.valueOf(request.getAccountStatus().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("Invalid account_status. Must be APPROVED or SUSPENDED");
        }

        driver.setApprovalStatus(newStatus);
        if (newStatus == DriverApprovalStatus.SUSPENDED) {
            driver.setAcceptingTrips(false);
            driver.setActivityStatus(DriverActivityStatus.OFFLINE);
        }

        driver = driverRepository.save(driver);
        return mapToDriverResponse(driver);
    }

    @Override
    @Transactional
    public VehicleResponse setActiveVehicle(UUID userId, VehicleInput request) {
        Driver driver = driverRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found for user: " + userId));

        // Deactivate existing active vehicles
        List<Vehicle> existingVehicles = vehicleRepository.findByDriverId(driver.getId());
        for (Vehicle v : existingVehicles) {
            v.setIsActive(false);
            vehicleRepository.save(v);
        }

        Vehicle vehicle = Vehicle.builder()
                .driverId(driver.getId())
                .plateNumber(request.getPlateNumber().trim().toUpperCase())
                .vehicleType(request.getVehicleType().trim().toUpperCase())
                .brand(request.getBrand().trim())
                .model(request.getModel().trim())
                .color(request.getColor().trim())
                .seatCount(request.getSeatCount())
                .isActive(true)
                .createdAt(OffsetDateTime.now())
                .build();

        vehicle = vehicleRepository.save(vehicle);

        // Sync to driver table legacy vehicle fields
        driver.setVehiclePlate(vehicle.getPlateNumber());
        driver.setVehicleModel(vehicle.getModel());
        driverRepository.save(driver);

        return mapToVehicleResponse(vehicle);
    }

    @Override
    public VehicleResponse getActiveVehicle(UUID userId) {
        Driver driver = driverRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found for user: " + userId));

        Vehicle vehicle = vehicleRepository.findByDriverIdAndIsActiveTrue(driver.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No active vehicle found for driver"));

        return mapToVehicleResponse(vehicle);
    }

    @Override
    @Transactional
    public AvailabilityResponse setAvailability(UUID userId, AvailabilityInput request) {
        Driver driver = driverRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found for user: " + userId));

        if (Boolean.TRUE.equals(request.getAcceptingTrips())) {
            // CONTRACT rule: Bật chỉ được khi APPROVED và có xe active
            if (driver.getApprovalStatus() != DriverApprovalStatus.APPROVED) {
                throw new InvalidInputException("Driver must be APPROVED before accepting trips");
            }
            if (vehicleRepository.findByDriverIdAndIsActiveTrue(driver.getId()).isEmpty()) {
                throw new InvalidInputException("Driver must have an active vehicle before accepting trips");
            }
        }

        driver.setAcceptingTrips(request.getAcceptingTrips());
        driver.setLastSeenAt(OffsetDateTime.now());
        driver.setActivityStatus(Boolean.TRUE.equals(request.getAcceptingTrips()) ? DriverActivityStatus.AVAILABLE : DriverActivityStatus.OFFLINE);

        driver = driverRepository.save(driver);

        return AvailabilityResponse.builder()
                .acceptingTrips(driver.getAcceptingTrips())
                .lastSeenAt(driver.getLastSeenAt())
                .build();
    }

    @Override
    @Transactional
    public LocationSessionResponse startLocationSession(UUID userId) {
        Driver driver = driverRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found for user: " + userId));

        UUID sessionId = UUID.randomUUID();
        OffsetDateTime startedAt = OffsetDateTime.now();

        driver.setCurrentSessionId(sessionId);
        driver.setCurrentSequenceNumber(0L);
        driverRepository.save(driver);

        return LocationSessionResponse.builder()
                .sessionId(sessionId)
                .startedAt(startedAt)
                .build();
    }

    @Override
    @Transactional
    public Ack heartbeat(UUID userId) {
        Driver driver = driverRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found for user: " + userId));

        driver.setLastSeenAt(OffsetDateTime.now());
        driverRepository.save(driver);

        return Ack.of(true);
    }

    @Override
    @Transactional
    public LocationResponse updateLocation(UUID userId, LocationInput request) {
        Driver driver = driverRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found for user: " + userId));

        // CONTRACT rule: Location session cũ và seq thấp không ghi đè vị trí mới
        if (driver.getCurrentSessionId() != null && driver.getCurrentSessionId().equals(request.getSessionId())) {
            if (driver.getCurrentSequenceNumber() != null && request.getSequenceNumber() <= driver.getCurrentSequenceNumber()) {
                throw new InvalidInputException("Sequence number must be greater than current sequence number (" + driver.getCurrentSequenceNumber() + ")");
            }
        }

        driver.setCurrentSessionId(request.getSessionId());
        driver.setCurrentSequenceNumber(request.getSequenceNumber());
        driver.setLatitude(request.getPoint().getLatitude());
        driver.setLongitude(request.getPoint().getLongitude());
        driver.setLastLocationUpdatedAt(request.getRecordedAt());
        driver.setLastSeenAt(OffsetDateTime.now());

        driver = driverRepository.save(driver);

        return LocationResponse.builder()
                .point(request.getPoint())
                .updatedAt(driver.getLastLocationUpdatedAt())
                .build();
    }

    @Override
    public LocationResponse getMyLocation(UUID userId) {
        Driver driver = driverRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found for user: " + userId));

        if (driver.getLatitude() == null || driver.getLongitude() == null) {
            throw new ResourceNotFoundException("No location has been reported yet for this driver");
        }

        PointDto point = PointDto.builder()
                .latitude(driver.getLatitude())
                .longitude(driver.getLongitude())
                .build();

        return LocationResponse.builder()
                .point(point)
                .updatedAt(driver.getLastLocationUpdatedAt() != null ? driver.getLastLocationUpdatedAt() : OffsetDateTime.now())
                .build();
    }

    // =========================================================================
    // LEGACY METHODS KEPT FOR BACKWARD COMPATIBILITY
    // =========================================================================

    @Override
    public DriverResponse registerDriver(DriverRegisterRequest request) {
        log.info("Registering driver (legacy): username={}, gplx={}", request.getUsername(), request.getLicenseNumber());

        if (driverRepository.existsByLicenseNumber(request.getLicenseNumber())) {
            throw new ResourceAlreadyExistException("Số giấy phép lái xe đã được đăng ký!");
        }
        if (driverRepository.existsByIdentityCard(request.getIdentityCard())) {
            throw new ResourceAlreadyExistException("Số căn cước công dân đã được đăng ký!");
        }
        if (driverRepository.existsByVehiclePlate(request.getVehiclePlate())) {
            throw new ResourceAlreadyExistException("Biển số xe đã được đăng ký trên hệ thống!");
        }

        UserProtoResponse userProto = userGrpcClient.registerDriverUser(
                request.getUsername(),
                request.getPassword(),
                request.getFirstName(),
                request.getLastName(),
                request.getPhone(),
                request.getEmail()
        );

        UUID userId = UUID.fromString(userProto.getId());

        Driver driver = Driver.builder()
                .driverId(UUID.randomUUID())
                .userId(userId)
                .identityCard(request.getIdentityCard())
                .licenseNumber(request.getLicenseNumber())
                .approvalStatus(DriverApprovalStatus.PENDING)
                .vehicleType(request.getVehicleType())
                .vehiclePlate(request.getVehiclePlate())
                .vehicleModel(request.getVehicleModel())
                .activityStatus(DriverActivityStatus.OFFLINE)
                .acceptingTrips(false)
                .createdAt(OffsetDateTime.now())
                .build();

        try {
            driver = driverRepository.save(driver);
        } catch (Exception ex) {
            log.error("Failed to save driver. Rolling back user...", ex);
            userGrpcClient.rollbackUser(userId.toString());
            throw new RuntimeException("Đăng ký tài xế thất bại, hệ thống đã hoàn tác tạo tài khoản.", ex);
        }

        return mapToDriverResponse(driver);
    }

    @Override
    public DriverResponse getDriverProfile(UUID userId) {
        Driver driver = driverRepository.findByIdOrUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ tài xế với ID: " + userId));

        return mapToDriverResponse(driver);
    }

    private DriverResponse mapToDriverResponse(Driver driver) {
        String statusName = driver.getApprovalStatus() != null ? driver.getApprovalStatus().name() : "PENDING";
        return DriverResponse.builder()
                .id(driver.getId())
                .userId(driver.getUserId())
                .licenseNumber(driver.getLicenseNumber())
                .accountStatus(statusName)
                .acceptingTrips(Boolean.TRUE.equals(driver.getAcceptingTrips()))
                .createdAt(driver.getCreatedAt() != null ? driver.getCreatedAt() : OffsetDateTime.now())
                .approvalStatus(driver.getApprovalStatus())
                .identityCard(driver.getIdentityCard())
                .vehicleType(driver.getVehicleType())
                .vehiclePlate(driver.getVehiclePlate())
                .vehicleModel(driver.getVehicleModel())
                .activityStatus(driver.getActivityStatus())
                .latitude(driver.getLatitude())
                .longitude(driver.getLongitude())
                .build();
    }

    private VehicleResponse mapToVehicleResponse(Vehicle vehicle) {
        return VehicleResponse.builder()
                .id(vehicle.getId())
                .driverId(vehicle.getDriverId())
                .plateNumber(vehicle.getPlateNumber())
                .vehicleType(vehicle.getVehicleType())
                .brand(vehicle.getBrand())
                .model(vehicle.getModel())
                .color(vehicle.getColor())
                .seatCount(vehicle.getSeatCount())
                .isActive(vehicle.getIsActive())
                .build();
    }
}
