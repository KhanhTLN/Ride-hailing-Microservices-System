package com.threecats.userservice.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="refresh_session", indexes={@Index(name="idx_refresh_family", columnList="family_id")})
@Getter @Setter @NoArgsConstructor
public class RefreshSession {
    @Id @Column(length=64) private String tokenHash;
    @Column(nullable=false) private UUID userId;
    @Column(nullable=false) private UUID familyId;
    @Column(nullable=false) private Instant expiresAt;
    private Instant revokedAt;
}
