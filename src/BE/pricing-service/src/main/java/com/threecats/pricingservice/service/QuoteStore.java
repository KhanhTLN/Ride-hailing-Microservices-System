package com.threecats.pricingservice.service;
import com.threecats.pricingservice.model.QuoteRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** Accepted immutable quotes survive service restarts and remain usable for final fare. */
@Component @RequiredArgsConstructor
public class QuoteStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    public void lockKey(String key) { jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,43))",Object.class,key); }
    public void save(String key,QuoteRecord quote) {
        try { jdbc.update("INSERT INTO pricing_quote(id,passenger_id,idempotency_key,payload) VALUES(?,?,?,CAST(? AS jsonb))",
            UUID.fromString(quote.getId()),UUID.fromString(quote.getPassengerId()),key,mapper.writeValueAsString(quote)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    public Optional<QuoteRecord> findById(String id) {
        UUID parsed;
        try { parsed=UUID.fromString(id); } catch(RuntimeException e) { return Optional.empty(); }
        return read("SELECT payload::text FROM pricing_quote WHERE id=?",parsed);
    }
    public Optional<QuoteRecord> findByIdempotencyKey(String key) { return read("SELECT payload::text FROM pricing_quote WHERE idempotency_key=?",key); }
    private Optional<QuoteRecord> read(String sql,Object key) {
        var values=jdbc.queryForList(sql,String.class,key);
        if(values.isEmpty()) return Optional.empty();
        try { return Optional.of(mapper.readValue(values.getFirst(),QuoteRecord.class)); }
        catch(Exception e) { throw new IllegalStateException("Invalid stored quote",e); }
    }
}
