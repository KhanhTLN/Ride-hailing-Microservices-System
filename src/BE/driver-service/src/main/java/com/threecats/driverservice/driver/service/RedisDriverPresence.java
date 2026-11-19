package com.threecats.driverservice.driver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@ConditionalOnProperty(name = "driver.presence.redis.enabled", havingValue = "true")
class RedisDriverPresence {
    static final String PREFIX = "driver:presence:";
    private static final Logger LOG = LoggerFactory.getLogger(RedisDriverPresence.class);
    private static final Duration TTL = Duration.ofSeconds(30);
    // An older post-commit callback can arrive after a newer heartbeat.
    private static final RedisScript<Long> STORE_NEWER = RedisScript.of("""
            local previous = redis.call('GET', KEYS[1])
            if previous and tonumber(string.match(previous, '^%d+')) >= tonumber(ARGV[1]) then
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[1] .. ':' .. ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    RedisDriverPresence(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @TransactionalEventListener
    public void onHeartbeat(DriverHeartbeatAccepted heartbeat) {
        long remainingMillis = Duration.between(Instant.now(), heartbeat.receivedAt().plus(TTL)).toMillis();
        if (remainingMillis <= 0) {
            return;
        }
        try {
            redis.execute(STORE_NEWER, List.of(PREFIX + heartbeat.driverId()),
                    Long.toString(heartbeat.receivedAt().toEpochMilli()), heartbeat.sessionId().toString(),
                    Long.toString(remainingMillis));
        } catch (DataAccessException ex) {
            LOG.warn("Could not update Redis presence for driver_id={}", heartbeat.driverId(), ex);
        }
    }
}
