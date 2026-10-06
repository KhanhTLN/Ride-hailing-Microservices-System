package com.threecats.userservice.user.entity;

import com.threecats.userservice.enums.AccountStatus;
import com.threecats.userservice.enums.Role;
import jakarta.persistence.*;
import jakarta.validation.constraints.Pattern;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name="user")
// 1. Ghi đè lệnh DELETE mặc định của Hibernate
@SQLDelete(sql = "UPDATE \"user\" SET deleted_at = NOW(), status = 'INACTIVE' WHERE id = ?")
// 2. Tự động lọc bỏ các user đã xóa mềm khi SELECT
@SQLRestriction("deleted_at IS NULL")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String username;

    private String firstName;

    private String lastName;

    @Column(unique = true)
    private String phone;

    @Column(unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @CreationTimestamp
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    private OffsetDateTime deletedAt;
}
