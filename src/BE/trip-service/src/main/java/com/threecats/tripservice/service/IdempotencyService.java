package com.threecats.tripservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.threecats.tripservice.exception.TripApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.HttpStatus;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

@Service @RequiredArgsConstructor
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager manager;
    public String scoped(UUID caller,String operation,String key) {
        if (key==null || key.isBlank() || key.length()>128) throw new TripApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_ARGUMENT","Idempotency-Key is required (1..128 characters)");
        return caller+":"+operation+":"+key;
    }
    public <T> Optional<T> replay(String key,Object request,Class<T> type) {
        var rows=jdbc.queryForList("SELECT request_hash,response_body::text AS body FROM idempotency_record WHERE key=?",key);
        if(rows.isEmpty()) return Optional.empty();
        var row=rows.getFirst();
        if(!hash(request).equals(row.get("request_hash"))) throw conflict();
        if(row.get("body")==null) throw new TripApiException(HttpStatus.CONFLICT,"OPERATION_IN_PROGRESS","Request is pending",true);
        try { return Optional.of(mapper.readValue((String)row.get("body"),type)); }
        catch(Exception e) { throw new IllegalStateException("Invalid stored idempotency result",e); }
    }
    public <T> T execute(UUID caller,String key,Object request,int status,Class<T> type,Supplier<T> action) {
        return new TransactionTemplate(manager).execute(tx -> {
            int inserted=jdbc.update("INSERT INTO idempotency_record(key,caller_id,request_hash,response_status,expires_at) VALUES(?,?,?,?,now()+interval '7 days') ON CONFLICT DO NOTHING",key,caller.toString(),hash(request),status);
            if(inserted==0) return replay(key,request,type).orElseThrow();
            T result=action.get();
            try { jdbc.update("UPDATE idempotency_record SET response_body=CAST(? AS jsonb) WHERE key=?",mapper.writeValueAsString(result),key); }
            catch(Exception e) { throw new IllegalStateException(e); }
            return result;
        });
    }
    public String hash(Object request) {
        try {
            ObjectMapper canonical=mapper.copy().configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true).configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.writeValueAsBytes(request)));
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private TripApiException conflict() { return new TripApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","The key was already used with a different request"); }
}
