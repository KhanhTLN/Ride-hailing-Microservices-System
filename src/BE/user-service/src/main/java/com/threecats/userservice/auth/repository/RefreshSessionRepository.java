package com.threecats.userservice.auth.repository;

import com.threecats.userservice.auth.entity.RefreshSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession,String> {
    @Query("select r.familyId from RefreshSession r where r.tokenHash=:hash")
    Optional<UUID> family(@Param("hash") String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshSession r where r.tokenHash=:hash")
    Optional<RefreshSession> lock(@Param("hash") String hash);
    @Modifying
    @Query("update RefreshSession r set r.revokedAt=:now where r.familyId=:family and r.revokedAt is null")
    int revokeFamily(@Param("family") UUID family, @Param("now") Instant now);
}
