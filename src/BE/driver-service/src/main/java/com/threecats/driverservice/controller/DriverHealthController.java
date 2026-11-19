package com.threecats.driverservice.controller;

import com.threecats.common.dto.response.HealthResponse;
import com.threecats.common.context.CorrelationContext;
import com.threecats.common.exception.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@RestController
@RequestMapping("/internal/driver/health")
public class DriverHealthController {

    private final DataSource dataSource;

    public DriverHealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/live")
    public ResponseEntity<HealthResponse> live() {
        return ResponseEntity.ok(HealthResponse.serving());
    }

    @GetMapping("/ready")
    public ResponseEntity<?> ready() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(2)) {
                return ResponseEntity.ok(HealthResponse.serving());
            }
        } catch (SQLException ignored) {
            // Report dependency failure without exposing connection details.
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of("DEPENDENCY_UNAVAILABLE", "Driver database is unavailable",
                        CorrelationContext.get(), true));
    }
}
