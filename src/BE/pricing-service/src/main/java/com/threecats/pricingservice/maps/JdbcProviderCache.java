package com.threecats.pricingservice.maps;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.function.Supplier;

/** Shared cache/rate gate; coalesces identical misses across app replicas.
 * A session advisory lock is used, never a transaction held during provider I/O.
 * All SQL uses the same leased connection, including cache writes, so waiters
 * cannot exhaust the pool while the first request needs another connection.
 */
@Component @RequiredArgsConstructor
public class JdbcProviderCache implements ProviderCache {
    private final DataSource dataSource;
    @Override public String load(String provider,String key,int intervalMs,int ttlSeconds,Supplier<String> fetch) {
        try(Connection connection=dataSource.getConnection()) {
            if(!connection.getAutoCommit()) throw new IllegalStateException("Provider I/O must be outside a transaction");
            boolean locked=false;
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
            try {
                do {
                    try(var statement=connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?,91301))")) {
                        statement.setString(1,key);
                        try(var result=statement.executeQuery()) { result.next(); locked=result.getBoolean(1); }
                    }
                    if(!locked) {
                        if(System.nanoTime()>deadline) throw MapsException.limited();
                        Thread.sleep(80);
                    }
                } while(!locked);
                try(var statement=connection.prepareStatement("SELECT payload::text FROM map_response_cache WHERE cache_key=? AND expires_at>clock_timestamp()")) {
                    statement.setString(1,key);
                    try(var result=statement.executeQuery()) { if(result.next()) return result.getString(1); }
                }
                try(var statement=connection.prepareStatement("""
                    INSERT INTO map_provider_gate(provider,next_allowed_at)
                    VALUES(?,clock_timestamp()+(? * interval '1 millisecond'))
                    ON CONFLICT(provider) DO UPDATE SET next_allowed_at=excluded.next_allowed_at
                    WHERE map_provider_gate.next_allowed_at<=clock_timestamp() RETURNING provider
                    """)) {
                    statement.setString(1,provider); statement.setInt(2,Math.max(1100,intervalMs));
                    try(var result=statement.executeQuery()) { if(!result.next()) throw MapsException.limited(); }
                }
                String body=fetch.get();
                try(var cleanup=connection.prepareStatement("DELETE FROM map_response_cache WHERE cache_key IN (SELECT cache_key FROM map_response_cache WHERE expires_at<=clock_timestamp() LIMIT 100)")) {
                    cleanup.executeUpdate();
                }
                try(var statement=connection.prepareStatement("""
                    INSERT INTO map_response_cache(cache_key,payload,expires_at)
                    VALUES(?,CAST(? AS jsonb),clock_timestamp()+(? * interval '1 second'))
                    ON CONFLICT(cache_key) DO UPDATE SET payload=excluded.payload,expires_at=excluded.expires_at
                    """)) {
                    statement.setString(1,key); statement.setString(2,body); statement.setInt(3,Math.max(60,ttlSeconds)); statement.executeUpdate();
                }
                return body;
            } finally {
                if(locked) try(var statement=connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?,91301))")) {
                    statement.setString(1,key); statement.execute();
                }
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); throw MapsException.unavailable();
        } catch(java.sql.SQLException e) { throw MapsException.unavailable(); }
    }
}
