package com.threecats.userservice.auth.entity;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="registration_request")
public class RegistrationRequestRecord {
    @Id @Column(length=128) private String key;
    @Column(nullable=false,length=64) private String requestHash;
    @Column(length=255) private String passwordHash;
    @Column(columnDefinition="TEXT") private String responseBody;
    @Column(nullable=false) private Instant expiresAt;
    protected RegistrationRequestRecord() {}
}
