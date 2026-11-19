package com.threecats.driverservice.grpc;

import io.grpc.*;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

@Component
@GlobalServerInterceptor
public class DriverGrpcSecurity implements ServerInterceptor {
    static final Context.Key<String> CALLER = Context.key("driver-caller");
    static final Context.Key<String> CORRELATION = Context.key("driver-correlation");
    static final Metadata.Key<String> CORRELATION_HEADER = key("x-correlation-id");
    private static final Set<String> MATCHING = Set.of(
            "GetDriver", "FindNearbyDrivers", "ReserveDriver", "GetReservation", "ReleaseReservation");
    private static final Set<String> TRIP = Set.of(
            "GetDriver", "GetReservation", "ConfirmReservation", "ReleaseReservation", "GetDriverLocation");

    private final JwtParser parser;

    public DriverGrpcSecurity(@Value("${driver.grpc.token-issuer:}") String issuer,
                              @Value("${driver.grpc.token-public-key:}") String publicKey) throws GeneralSecurityException {
        parser = issuer.isBlank() || publicKey.isBlank() ? null : Jwts.parser()
                .verifyWith(decodeKey(publicKey))
                .requireIssuer(issuer)
                .requireAudience("driver-service")
                .build();
    }

    @Override
    public <Req, Res> ServerCall.Listener<Req> interceptCall(ServerCall<Req, Res> call,
            Metadata headers, ServerCallHandler<Req, Res> next) {
        if (!call.getMethodDescriptor().getFullMethodName().startsWith("ds03.driver.v1.DriverService/")) {
            return next.startCall(call, headers);
        }
        String correlation = headers.get(CORRELATION_HEADER);
        if (!validUuid(correlation)) {
            return reject(call, Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", UUID.randomUUID().toString());
        }
        Claims claims;
        try {
            String bearer = headers.get(key("authorization"));
            if (parser == null || bearer == null || !bearer.startsWith("Bearer ")) {
                return reject(call, Status.UNAUTHENTICATED, "UNAUTHENTICATED", correlation);
            }
            var signed = parser.parseSignedClaims(bearer.substring(7));
            if (!"RS256".equals(signed.getHeader().getAlgorithm())) {
                return reject(call, Status.UNAUTHENTICATED, "UNAUTHENTICATED", correlation);
            }
            claims = signed.getPayload();
            if (claims.getExpiration() == null || claims.getExpiration().toInstant().isAfter(Instant.now().plusSeconds(300))) {
                return reject(call, Status.UNAUTHENTICATED, "UNAUTHENTICATED", correlation);
            }
        } catch (RuntimeException ex) {
            return reject(call, Status.UNAUTHENTICATED, "UNAUTHENTICATED", correlation);
        }
        String caller = claims.getSubject();
        String method = call.getMethodDescriptor().getBareMethodName();
        Object scope = claims.get("scope");
        boolean allowed = scope instanceof String scopes
                && (("matching-service".equals(caller) && MATCHING.contains(method) && hasScope(scopes, "driver:match"))
                || ("trip-service".equals(caller) && TRIP.contains(method) && hasScope(scopes, "driver:trip")));
        if (!allowed) {
            return reject(call, Status.PERMISSION_DENIED, "FORBIDDEN", correlation);
        }
        Context context = Context.current().withValues(CALLER, caller, CORRELATION, correlation);
        return Contexts.interceptCall(context, call, headers, next);
    }

    static Metadata.Key<String> key(String name) {
        return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
    }

    static boolean validUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean hasScope(String scopes, String required) {
        return Arrays.asList(scopes.split("\s+")).contains(required);
    }

    private static PublicKey decodeKey(String value) throws GeneralSecurityException {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(value)));
    }

    private <Req, Res> ServerCall.Listener<Req> reject(ServerCall<Req, Res> call, Status status,
            String code, String correlation) {
        Metadata trailers = new Metadata();
        trailers.put(key("error-code"), code);
        trailers.put(CORRELATION_HEADER, correlation);
        call.close(status, trailers);
        return new ServerCall.Listener<>() {};
    }
}
