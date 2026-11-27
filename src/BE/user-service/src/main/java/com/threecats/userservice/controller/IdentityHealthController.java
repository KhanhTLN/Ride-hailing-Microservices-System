package com.threecats.userservice.controller;

import com.threecats.common.dto.response.HealthResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@lombok.RequiredArgsConstructor
@RestController
@RequestMapping("/internal/identity/health")
public class IdentityHealthController {
    private final javax.sql.DataSource dataSource;

    @GetMapping("/live")
    public ResponseEntity<HealthResponse> live() {
        return ResponseEntity.ok(HealthResponse.serving());
    }

    @GetMapping("/ready")
    public ResponseEntity<?> ready() {
        try(var connection=dataSource.getConnection()) {
            if(connection.isValid(2)) return ResponseEntity.ok(HealthResponse.serving());
        } catch(java.sql.SQLException ignored) {}
        return ResponseEntity.status(503).body(java.util.Map.of("code","DEPENDENCY_UNAVAILABLE"));
    }
}
