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
class RedisDriverLocations {
    private static final Logger LOG = LoggerFactory.getLogger(RedisDriverLocations.class);
    private static final Duration TTL = Duration.ofSeconds(30);
    // Use a common hash tag because this script updates several Redis keys together.
    private static final String PREFIX = "driver:{geo}:";
    private static final RedisScript<Long> APPLY_LOCATION = RedisScript.of("""
            local version = redis.call('GET', KEYS[2])
            if version and tonumber(version) >= tonumber(ARGV[1]) then
                return 0
            end
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
            redis.call('ZREM', KEYS[3], ARGV[3])
            redis.call('ZREM', KEYS[4], ARGV[3])
            if ARGV[4] == '' then
                redis.call('DEL', KEYS[1])
                return 1
            end
            local geo = KEYS[3]
            if ARGV[4] == 'MOTORBIKE' then geo = KEYS[4] end
            redis.call('GEOADD', geo, ARGV[5], ARGV[6], ARGV[3])
            redis.call('SET', KEYS[1], ARGV[7], 'PX', ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    RedisDriverLocations(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @TransactionalEventListener
    public void onLocationChanged(DriverLocationChanged change) {
        Instant received = change.receivedAt();
        long remaining = Duration.between(Instant.now(), received.plus(TTL)).toMillis();
        if (remaining <= 0) {
            return;
        }

        String id = change.driverId().toString();
        var point = change.position();
        long revision = received.getEpochSecond() * 1_000_000 + received.getNano() / 1_000;
        try {
            redis.execute(APPLY_LOCATION,
                    List.of(PREFIX + "location:" + id, PREFIX + "revision:" + id,
                            PREFIX + "CAR", PREFIX + "MOTORBIKE"),
                    Long.toString(revision), Long.toString(remaining), id,
                    point == null ? "" : point.vehicleType(),
                    point == null ? "" : Double.toString(point.longitude()),
                    point == null ? "" : Double.toString(point.latitude()),
                    point == null ? "" : received.toEpochMilli() + ":" + point.sessionId() + ":"
                            + point.sequence() + ":" + point.longitude() + ":" + point.latitude());
        } catch (DataAccessException ex) {
            LOG.warn("Could not update Redis location for driver_id={}", id, ex);
        }
    }
}
