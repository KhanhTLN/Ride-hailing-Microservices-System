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
import org.springframework.context.ApplicationEventPublisher;
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
    private final DriverReservationService reservations;
    private final ApplicationEventPublisher events;

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
    @Transactional(readOnly = true)
    public List<DriverResponse> getAllDrivers() {
        return driverRepository.findAll().stream()
                .map(this::mapToDriverResponse)
                .toList();
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
        Driver driver = driverRepository.lockByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found for user: " + userId));

        reservations.ensureVehicleCanChange(driver.getId());

        String plate = request.getPlateNumber().trim().toUpperCase(java.util.Locale.ROOT);
        String type = request.getVehicleType().trim().toUpperCase(java.util.Locale.ROOT);
        if (!type.equals("MOTORBIKE") && !type.equals("CAR")) {
            throw new InvalidInputException("Unsupported vehicle type");
        }

        List<Vehicle> existing = vehicleRepository.findByDriverId(driver.getId());
        Vehicle vehicle = existing.stream()
                .filter(candidate -> candidate.getPlateNumber().trim().equalsIgnoreCase(plate))
                .findFirst()
                .orElseGet(() -> Vehicle.builder().driverId(driver.getId()).build());

        existing.stream()
                .filter(candidate -> candidate != vehicle && Boolean.TRUE.equals(candidate.getIsActive()))
                .forEach(candidate -> candidate.setIsActive(false));
        vehicleRepository.flush();

        vehicle.setPlateNumber(plate);
        vehicle.setVehicleType(type);
        vehicle.setBrand(request.getBrand().trim());
        vehicle.setModel(request.getModel().trim());
        vehicle.setColor(request.getColor().trim());
        vehicle.setSeatCount(request.getSeatCount());
        vehicle.setIsActive(true);
        Vehicle savedVehicle = vehicleRepository.save(vehicle);

        // Sync to driver table legacy vehicle fields
        driver.setVehiclePlate(vehicle.getPlateNumber());
        driver.setVehicleModel(vehicle.getModel());
        driverRepository.save(driver);

        return mapToVehicleResponse(savedVehicle);
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
            // CONTRACT rule: Báº­t chá»‰ Ä‘Æ°á»£c khi APPROVED vÃ  cÃ³ xe active
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
        Driver driver = lockDriver(userId);
        UUID sessionId = UUID.randomUUID();
        driver.setCurrentSessionId(sessionId);
        driver.setCurrentSequenceNumber(null);
        driver.setLatitude(null);
        driver.setLongitude(null);
        driver.setLastLocationUpdatedAt(null);
        driver.setLastLocationRecordedAt(null);
        driver.setLastSeenAt(null);
        return LocationSessionResponse.builder()
                .sessionId(sessionId)
                .startedAt(OffsetDateTime.now(java.time.ZoneOffset.UTC))
                .build();
    }

    @Override
    @Transactional
    public Ack heartbeat(UUID userId, UUID sessionId) {
        Driver driver = lockDriver(userId);
        requireSession(driver, sessionId);
        OffsetDateTime receivedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        driver.setLastSeenAt(receivedAt);
        events.publishEvent(new DriverHeartbeatAccepted(driver.getId(), sessionId, receivedAt.toInstant()));
        return Ack.of(true);
    }

    @Override
    @Transactional
    public LocationResponse updateLocation(UUID userId, LocationInput request) {
        Driver driver = lockDriver(userId);
        requireSession(driver, request.getSessionId());
        Long currentSequence = driver.getCurrentSequenceNumber();
        OffsetDateTime recordedAt = request.getRecordedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        PointDto point = request.getPoint();
        if (currentSequence != null && request.getSequenceNumber() <= currentSequence) {
            if (request.getSequenceNumber().equals(currentSequence)
                    && point.getLatitude().equals(driver.getLatitude())
                    && point.getLongitude().equals(driver.getLongitude())
                    && driver.getLastLocationRecordedAt() != null
                    && recordedAt.isEqual(driver.getLastLocationRecordedAt())) {
                return locationResponse(driver);
            }
            throw new LocationVersionConflict("Location sequence conflicts with the stored update");
        }

        OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        if (recordedAt.isBefore(now.minusSeconds(60)) || recordedAt.isAfter(now.plusSeconds(60))) {
            throw new InvalidInputException("recorded_at must be within 60 seconds of server time");
        }
        driver.setCurrentSequenceNumber(request.getSequenceNumber());
        driver.setLatitude(point.getLatitude());
        driver.setLongitude(point.getLongitude());
        driver.setLastLocationRecordedAt(recordedAt);
        driver.setLastLocationUpdatedAt(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        return locationResponse(driver);
    }

    @Override
    public LocationResponse getMyLocation(UUID userId) {
        Driver driver = driverRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found"));
        if (driver.getLastLocationRecordedAt() == null) {
            throw new ResourceNotFoundException("No location has been reported for the current session");
        }
        return locationResponse(driver);
    }

    private Driver lockDriver(UUID userId) {
        return driverRepository.lockByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found"));
    }

    private void requireSession(Driver driver, UUID sessionId) {
        if (!sessionId.equals(driver.getCurrentSessionId())) {
            throw new LocationVersionConflict("Location session is no longer current");
        }
    }

    private LocationResponse locationResponse(Driver driver) {
        return LocationResponse.builder()
                .driverId(driver.getId())
                .sessionId(driver.getCurrentSessionId())
                .sequenceNumber(driver.getCurrentSequenceNumber())
                .point(new PointDto(driver.getLatitude(), driver.getLongitude()))
                .recordedAt(driver.getLastLocationRecordedAt())
                .receivedAt(driver.getLastLocationUpdatedAt())
                .build();
    }

    @Override
    public DriverResponse registerDriver(DriverRegisterRequest request) {
        log.info("Registering driver (legacy): username={}, gplx={}", request.getUsername(), request.getLicenseNumber());

        if (driverRepository.existsByLicenseNumber(request.getLicenseNumber())) {
            throw new ResourceAlreadyExistException("Sá»‘ giáº¥y phÃ©p lÃ¡i xe Ä‘Ã£ Ä‘Æ°á»£c Ä‘Äƒng kÃ½!");
        }
        if (driverRepository.existsByIdentityCard(request.getIdentityCard())) {
            throw new ResourceAlreadyExistException("Sá»‘ cÄƒn cÆ°á»›c cÃ´ng dÃ¢n Ä‘Ã£ Ä‘Æ°á»£c Ä‘Äƒng kÃ½!");
        }
        if (driverRepository.existsByVehiclePlate(request.getVehiclePlate())) {
            throw new ResourceAlreadyExistException("Biá»ƒn sá»‘ xe Ä‘Ã£ Ä‘Æ°á»£c Ä‘Äƒng kÃ½ trÃªn há»‡ thá»‘ng!");
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
            throw new RuntimeException("ÄÄƒng kÃ½ tÃ i xáº¿ tháº¥t báº¡i, há»‡ thá»‘ng Ä‘Ã£ hoÃ n tÃ¡c táº¡o tÃ i khoáº£n.", ex);
        }

        return mapToDriverResponse(driver);
    }

    @Override
    public DriverResponse getDriverProfile(UUID userId) {
        Driver driver = driverRepository.findByIdOrUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("KhÃ´ng tÃ¬m tháº¥y há»“ sÆ¡ tÃ i xáº¿ vá»›i ID: " + userId));

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
