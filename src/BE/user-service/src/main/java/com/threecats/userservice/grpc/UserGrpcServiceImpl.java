package com.threecats.userservice.grpc;

import com.threecats.common.exception.resource.ResourceAlreadyExistException;
import com.threecats.common.grpc.*;
import com.threecats.userservice.auth.service.AuthService;
import com.threecats.userservice.user.dto.response.UserResponse;
import com.threecats.userservice.user.entity.User;
import com.threecats.userservice.user.repository.UserRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserGrpcServiceImpl extends UserGrpcServiceGrpc.UserGrpcServiceImplBase {

    private final AuthService authService;
    private final UserRepository userRepository;

    @Override
    public void registerDriverUser(RegisterDriverUserRequest request, StreamObserver<UserProtoResponse> responseObserver) {
        try {
            log.info("[gRPC Server] Nhan yeu cau dang ky Driver User cho username: {}", request.getUsername());

            UserResponse userResponse = authService.registerDriverUser(
                    request.getUsername(),
                    request.getPassword(),
                    request.getFirstName(),
                    request.getLastName(),
                    request.getPhone(),
                    request.getEmail()
            );

            UserProtoResponse response = UserProtoResponse.newBuilder()
                    .setId(userResponse.getId().toString())
                    .setUsername(userResponse.getUsername() != null ? userResponse.getUsername() : "")
                    .setFirstName(userResponse.getFirstName() != null ? userResponse.getFirstName() : "")
                    .setLastName(userResponse.getLastName() != null ? userResponse.getLastName() : "")
                    .setPhone(userResponse.getPhone() != null ? userResponse.getPhone() : "")
                    .setEmail(userResponse.getEmail() != null ? userResponse.getEmail() : "")
                    .setRole(userResponse.getRole() != null ? userResponse.getRole().name() : "")
                    .setStatus(userResponse.getStatus() != null ? userResponse.getStatus().name() : "")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
            log.info("[gRPC Server] Tao Driver User thanh cong! ID: {}", userResponse.getId());

        } catch (ResourceAlreadyExistException e) {
            log.warn("[gRPC Server] Du lieu bi trung lap: {}", e.getMessage());
            responseObserver.onError(Status.ALREADY_EXISTS
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (IllegalArgumentException e) {
            log.warn("[gRPC Server] Du lieu khong hop le: {}", e.getMessage());
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            log.error("[gRPC Server] Loi he thong khi dang ky Driver User: ", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal server error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void getUserById(GetUserByIdRequest request, StreamObserver<UserProtoResponse> responseObserver) {
        try {
            UUID userId = UUID.fromString(request.getUserId());
            User user = userRepository.findById(userId).orElse(null);

            if (user == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("User not found with ID: " + request.getUserId())
                        .asRuntimeException());
                return;
            }

            UserProtoResponse response = UserProtoResponse.newBuilder()
                    .setId(user.getId().toString())
                    .setUsername(user.getUsername() != null ? user.getUsername() : "")
                    .setFirstName(user.getFirstName() != null ? user.getFirstName() : "")
                    .setLastName(user.getLastName() != null ? user.getLastName() : "")
                    .setPhone(user.getPhone() != null ? user.getPhone() : "")
                    .setEmail(user.getEmail() != null ? user.getEmail() : "")
                    .setRole(user.getRole() != null ? user.getRole().name() : "")
                    .setStatus(user.getStatus() != null ? user.getStatus().name() : "")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid UUID format: " + request.getUserId())
                    .asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal server error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void rollbackUser(RollbackUserRequest request, StreamObserver<RollbackUserResponse> responseObserver) {
        try {
            log.warn("[gRPC Server] Thuc hien Compensating action: Rollback User ID: {}", request.getUserId());
            UUID userId = UUID.fromString(request.getUserId());
            authService.rollbackUser(userId);

            RollbackUserResponse response = RollbackUserResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Rollback user successfully")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("[gRPC Server] Loi khi rollback User: ", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Rollback failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }
}
