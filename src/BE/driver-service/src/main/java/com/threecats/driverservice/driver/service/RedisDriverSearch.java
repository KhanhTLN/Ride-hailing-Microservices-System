package com.threecats.driverservice.driver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "driver.presence.redis.enabled", havingValue = "true")
public class RedisDriverSearch {
    private static final Logger LOG = LoggerFactory.getLogger(RedisDriverSearch.class);
    private static final RedisScript<List> SEARCH = RedisScript.of("""
            return redis.call('GEOSEARCH', KEYS[1], 'FROMLONLAT', ARGV[1], ARGV[2],
                              'BYRADIUS', ARGV[3], 'M', 'ASC', 'COUNT', 200)
            """, List.class);

    private final StringRedisTemplate redis;

    RedisDriverSearch(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Map<UUID, PositionVersion> find(double longitude, double latitude, int radius,
                                           String type, Set<UUID> excluded) {
        if (Math.abs(latitude) > 85.05112878) {
            return Map.of();
        }
        String prefix = RedisDriverLocations.PREFIX;
        try {
            List<?> members = redis.execute(SEARCH, List.of(prefix + type),
                    Double.toString(longitude), Double.toString(latitude), Integer.toString(radius));
            if (members == null || members.isEmpty()) {
                return Map.of();
            }
            List<UUID> ids = members.stream().map(Object::toString).map(UUID::fromString)
                    .filter(id -> !excluded.contains(id)).toList();
            if (ids.isEmpty()) {
                return Map.of();
            }
            List<String> locations = redis.opsForValue()
                    .multiGet(ids.stream().map(id -> prefix + "location:" + id).toList());
            List<String> heartbeats = redis.opsForValue()
                    .multiGet(ids.stream().map(id -> RedisDriverPresence.PREFIX + id).toList());
            if (locations == null || heartbeats == null) {
                return Map.of();
            }
            Map<UUID, PositionVersion> matches = new HashMap<>();
            for (int index = 0; index < ids.size(); index++) {
                String location = locations.get(index);
                String heartbeat = heartbeats.get(index);
                if (location == null || heartbeat == null) {
                    continue;
                }
                String[] parts = location.split(":", 5);
                if (parts.length == 5 && heartbeat.endsWith(":" + parts[1])) {
                    matches.put(ids.get(index), new PositionVersion(UUID.fromString(parts[1]), Long.parseLong(parts[2])));
                }
            }
            return matches;
        } catch (DataAccessException | IllegalArgumentException ex) {
            LOG.debug("Redis driver search unavailable, using PostgreSQL", ex);
            return Map.of();
        }
    }

    public record PositionVersion(UUID session, long sequence) {
    }
}
