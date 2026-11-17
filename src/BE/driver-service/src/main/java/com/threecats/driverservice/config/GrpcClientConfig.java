package com.threecats.driverservice.config;

import com.threecats.common.grpc.UserGrpcServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcClientConfig {

    @Bean
    public UserGrpcServiceGrpc.UserGrpcServiceBlockingStub userGrpcServiceBlockingStub(GrpcChannelFactory channelFactory) {
        return UserGrpcServiceGrpc.newBlockingStub(channelFactory.createChannel("user-service"));
    }
}
