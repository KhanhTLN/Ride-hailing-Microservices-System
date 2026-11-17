package com.threecats.driverservice.driver.service;

import com.threecats.common.exception.resource.ResourceAlreadyExistException;
import com.threecats.common.exception.resource.ResourceNotFoundException;
import com.threecats.common.grpc.UserProtoResponse;
import com.threecats.driverservice.driver.dto.request.DriverRegisterRequest;
import com.threecats.driverservice.driver.dto.response.DriverResponse;
import com.threecats.driverservice.driver.entity.Driver;
import com.threecats.driverservice.driver.repository.DriverRepository;
import com.threecats.driverservice.enums.DriverActivityStatus;
import com.threecats.driverservice.enums.DriverApprovalStatus;
import com.threecats.driverservice.grpc.UserGrpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DriverServiceImpl implements DriverService {

    private final DriverRepository driverRepository;
    private final UserGrpcClient userGrpcClient;

    @Override
    public DriverResponse registerDriver(DriverRegisterRequest request) {
        log.info("Bat dau quy trinh dang ky Tai xe moi: username={}, gplx={}", request.getUsername(), request.getLicenseNumber());

        // BƯỚC 1: Kiem tra trung lap thong tin phap ly trong driverdb truoc
        if (driverRepository.existsByLicenseNumber(request.getLicenseNumber())) {
            throw new ResourceAlreadyExistException("Số giấy phép lái xe đã được đăng ký!");
        }
        if (driverRepository.existsByIdentityCard(request.getIdentityCard())) {
            throw new ResourceAlreadyExistException("Số căn cước công dân đã được đăng ký!");
        }
        if (driverRepository.existsByVehiclePlate(request.getVehiclePlate())) {
            throw new ResourceAlreadyExistException("Biển số xe đã được đăng ký trên hệ thống!");
        }

        // BƯỚC 2: Goi sang user-service qua gRPC de tao tai khoan User voi role DRIVER
        UserProtoResponse userProto = userGrpcClient.registerDriverUser(
                request.getUsername(),
                request.getPassword(),
                request.getFirstName(),
                request.getLastName(),
                request.getPhone(),
                request.getEmail()
        );

        UUID userId = UUID.fromString(userProto.getId());

        // BƯỚC 3: Luu ho so tai xe vao driverdb voi trang thai cho duyet PENDING
        Driver driver = Driver.builder()
                .userId(userId)
                .identityCard(request.getIdentityCard())
                .licenseNumber(request.getLicenseNumber())
                .approvalStatus(DriverApprovalStatus.PENDING)
                .vehicleType(request.getVehicleType())
                .vehiclePlate(request.getVehiclePlate())
                .vehicleModel(request.getVehicleModel())
                .activityStatus(DriverActivityStatus.OFFLINE)
                .latitude(null)
                .longitude(null)
                .lastLocationUpdatedAt(null)
                .build();

        try {
            driver = driverRepository.save(driver);
            log.info("Luu ho so tai xe thanh cong cho userId: {}", userId);
        } catch (Exception ex) {
            // COMPENSATING ACTION: Neu luu DB driver that bai, phai rollback tai khoan user da tao qua gRPC
            log.error("Loi khi luu ho so Tai xe! Dang kich hoat Compensating Action (Rollback User)...", ex);
            userGrpcClient.rollbackUser(userId.toString());
            throw new RuntimeException("Đăng ký tài xế thất bại, hệ thống đã hoàn tác tạo tài khoản. Vui lòng thử lại!", ex);
        }

        return mapToDriverResponse(driver, userProto);
    }

    @Override
    public DriverResponse getDriverProfile(UUID userId) {
        Driver driver = driverRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ tài xế với ID: " + userId));

        // Goi gRPC sang user-service de lay thong tin ho ten, email, sdt
        UserProtoResponse userProto = userGrpcClient.getUserById(userId.toString());

        return mapToDriverResponse(driver, userProto);
    }

    private DriverResponse mapToDriverResponse(Driver driver, UserProtoResponse userProto) {
        String fullName = (userProto.getLastName() + " " + userProto.getFirstName()).trim();
        return DriverResponse.builder()
                .userId(driver.getUserId())
                .username(userProto.getUsername())
                .fullName(fullName.isEmpty() ? userProto.getUsername() : fullName)
                .phone(userProto.getPhone())
                .email(userProto.getEmail())
                .identityCard(driver.getIdentityCard())
                .licenseNumber(driver.getLicenseNumber())
                .approvalStatus(driver.getApprovalStatus())
                .vehicleType(driver.getVehicleType())
                .vehiclePlate(driver.getVehiclePlate())
                .vehicleModel(driver.getVehicleModel())
                .activityStatus(driver.getActivityStatus())
                .latitude(driver.getLatitude())
                .longitude(driver.getLongitude())
                .createdAt(driver.getCreatedAt())
                .build();
    }
}
