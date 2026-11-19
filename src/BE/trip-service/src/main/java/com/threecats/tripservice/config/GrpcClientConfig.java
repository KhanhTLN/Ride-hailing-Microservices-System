package com.threecats.tripservice.config;

import com.ds03.contracts.driver.v1.DriverServiceGrpc;
import com.ds03.contracts.pricing.v1.PricingServiceGrpc;
import com.threecats.common.context.CorrelationContext;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executor;
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

    @Value("${TRIP_DRIVER_TOKEN_FILE:}")
    private String driverTokenFile;

    @Value("${TRIP_DRIVER_TLS:false}")
    private boolean driverTls;

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
        var builder = ManagedChannelBuilder.forAddress(driverHost, driverPort)
                .keepAliveTime(30, TimeUnit.SECONDS);
        return (driverTls ? builder.useTransportSecurity() : builder.usePlaintext()).build();
    }

    @Bean
    public DriverServiceGrpc.DriverServiceBlockingStub driverServiceBlockingStub(ManagedChannel driverChannel) {
        return DriverServiceGrpc.newBlockingStub(driverChannel).withCallCredentials(new CallCredentials() {
            @Override
            public void applyRequestMetadata(RequestInfo request, Executor executor, MetadataApplier applier) {
                String correlation = CorrelationContext.get();
                String correlationId;
                try {
                    correlationId = UUID.fromString(correlation).toString();
                } catch (IllegalArgumentException ex) {
                    correlationId = UUID.randomUUID().toString();
                }
                String headerValue = correlationId;
                executor.execute(() -> {
                    try {
                        String token = driverTokenFile.isBlank() ? "" : Files.readString(Path.of(driverTokenFile)).strip();
                        if (token.isEmpty()) {
                            applier.fail(Status.UNAUTHENTICATED.withDescription("Driver service token is unavailable"));
                            return;
                        }
                        Metadata headers = new Metadata();
                        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
                        headers.put(Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER), headerValue);
                        applier.apply(headers);
                    } catch (IOException | IllegalArgumentException ex) {
                        applier.fail(Status.UNAUTHENTICATED.withDescription("Cannot read Driver service token"));
                    }
                });
            }
        });
    }
}
