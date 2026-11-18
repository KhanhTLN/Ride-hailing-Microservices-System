package com.threecats.userservice.controller;

import com.threecats.common.dto.response.HealthResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/identity/health")
public class IdentityHealthController {

    @GetMapping("/live")
    public ResponseEntity<HealthResponse> live() {
        return ResponseEntity.ok(HealthResponse.serving());
    }

    @GetMapping("/ready")
    public ResponseEntity<HealthResponse> ready() {
        return ResponseEntity.ok(HealthResponse.serving());
    }
}
