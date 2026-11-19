package com.threecats.tripservice.config;

import com.ds03.contracts.driver.v1.DriverServiceGrpc;
import com.ds03.contracts.pricing.v1.PricingServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class GrpcClientConfig {

    @Value("${pricing.grpc.host:localhost}")
    private String pricingHost;

    @Value("${pricing.grpc.port:9093}")
    private int pricingPort;

    @Value("${driver.grpc.host:localhost}")
    private String driverHost;

    @Value("${driver.grpc.port:9091}")
    private int driverPort;

    @Bean(destroyMethod = "shutdown")
    public ManagedChannel pricingChannel() {
        return ManagedChannelBuilder.forAddress(pricingHost, pricingPort)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .build();
    }

    @Bean
    public PricingServiceGrpc.PricingServiceBlockingStub pricingServiceBlockingStub(ManagedChannel pricingChannel) {
        return PricingServiceGrpc.newBlockingStub(pricingChannel);
    }

    @Bean(destroyMethod = "shutdown")
    public ManagedChannel driverChannel() {
        return ManagedChannelBuilder.forAddress(driverHost, driverPort)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .build();
    }

    @Bean
    public DriverServiceGrpc.DriverServiceBlockingStub driverServiceBlockingStub(ManagedChannel driverChannel) {
        return DriverServiceGrpc.newBlockingStub(driverChannel);
    }
}
