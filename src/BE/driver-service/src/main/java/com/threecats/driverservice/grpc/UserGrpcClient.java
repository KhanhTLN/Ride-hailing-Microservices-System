package com.threecats.driverservice.grpc;

import com.threecats.common.exception.resource.ResourceAlreadyExistException;
import com.threecats.common.exception.resource.ResourceNotFoundException;
import com.threecats.common.exception.validationAndRequest.InvalidInputException;
import com.threecats.common.grpc.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserGrpcClient {

    private final UserGrpcServiceGrpc.UserGrpcServiceBlockingStub userStub;

    public UserProtoResponse registerDriverUser(String username, String password, String firstName, String lastName, String phone, String email) {
        try {
            log.info("[gRPC Client] Goi User Service tao tai khoan Driver cho: {}", username);
            RegisterDriverUserRequest request = RegisterDriverUserRequest.newBuilder()
                    .setUsername(username)
                    .setPassword(password)
                    .setFirstName(firstName)
                    .setLastName(lastName)
                    .setPhone(phone)
                    .setEmail(email)
                    .build();

            return userStub.registerDriverUser(request);
        } catch (StatusRuntimeException e) {
            handleGrpcException("Dang ky tai khoan Driver qua gRPC", e);
            throw e;
        }
    }

    public UserProtoResponse getUserById(String userId) {
        try {
            log.info("[gRPC Client] Goi User Service lay thong tin User ID: {}", userId);
            GetUserByIdRequest request = GetUserByIdRequest.newBuilder()
                    .setUserId(userId)
                    .build();

            return userStub.getUserById(request);
        } catch (StatusRuntimeException e) {
            handleGrpcException("Lay thong tin User qua gRPC", e);
            throw e;
        }
    }

    public void rollbackUser(String userId) {
        try {
            log.warn("[gRPC Client] Goi User Service rollback User ID: {}", userId);
            RollbackUserRequest request = RollbackUserRequest.newBuilder()
                    .setUserId(userId)
                    .build();

            userStub.rollbackUser(request);
            log.info("[gRPC Client] Rollback User thanh cong!");
        } catch (StatusRuntimeException e) {
            log.error("[gRPC Client] Rollback User that bai: ", e);
        }
    }

    private void handleGrpcException(String action, StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        String description = e.getStatus().getDescription();
        log.error("[gRPC Client] {} that bai. Status: {}, Mo ta: {}", action, code, description);

        if (code == Status.Code.ALREADY_EXISTS) {
            throw new ResourceAlreadyExistException(description != null ? description : "Tai nguyen da ton tai!");
        } else if (code == Status.Code.NOT_FOUND) {
            throw new ResourceNotFoundException(description != null ? description : "Khong tim thay tai nguyen!");
        } else if (code == Status.Code.INVALID_ARGUMENT) {
            throw new InvalidInputException(description != null ? description : "Du lieu khong hop le!");
        } else {
            throw new RuntimeException("Loi ket noi gRPC toi User Service: " + description, e);
        }
    }
}
