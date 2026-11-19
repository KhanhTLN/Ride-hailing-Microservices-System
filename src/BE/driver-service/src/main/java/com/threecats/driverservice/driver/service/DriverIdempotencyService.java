package com.threecats.driverservice.driver.service;

import com.threecats.common.exception.BaseException;
import com.threecats.common.exception.validationAndRequest.InvalidInputException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

@Service
public class DriverIdempotencyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DriverIdempotencyService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public <T> T execute(String caller, String operation, String key, Object payload,
                         Class<T> responseType, Supplier<T> mutation) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new InvalidInputException("A valid Idempotency-Key is required");
        }
        String callerId = caller;
        String hash = hash(payload);
        int inserted = jdbc.update("""
                INSERT INTO public.driver_idempotency (caller, operation, idempotency_key, request_hash)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (caller, operation, idempotency_key) DO NOTHING
                """, callerId, operation, key, hash);
        if (inserted == 0) {
            return jdbc.queryForObject("""
                    SELECT request_hash, result::text AS result
                    FROM public.driver_idempotency
                    WHERE caller = ? AND operation = ? AND idempotency_key = ?
                    """, (row, index) -> {
                if (!hash.equals(row.getString("request_hash"))) {
                    throw new IdempotencyConflict();
                }
                return mapper.readValue(row.getString("result"), responseType);
            }, callerId, operation, key);
        }

        T result = mutation.get();
        jdbc.update("""
                UPDATE public.driver_idempotency SET result = CAST(? AS jsonb)
                WHERE caller = ? AND operation = ? AND idempotency_key = ?
                """, mapper.writeValueAsString(result), callerId, operation, key);
        return result;
    }

    private String hash(Object payload) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(payload)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static final class IdempotencyConflict extends BaseException {
        private IdempotencyConflict() {
            super(409, "IDEMPOTENCY_CONFLICT", "The idempotency key was already used with a different request");
        }
    }
}
