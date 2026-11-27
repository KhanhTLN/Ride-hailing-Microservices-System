package com.threecats.userservice.auth.service;
import com.threecats.userservice.auth.dto.request.RegisterRequest;
import com.threecats.userservice.user.dto.response.UserResponse;
import com.threecats.common.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.security.MessageDigest;
import java.util.function.Supplier;

@Service @RequiredArgsConstructor
public class RegistrationIdempotency {
    private final JdbcTemplate jdbc;
    private final org.springframework.security.crypto.password.PasswordEncoder passwords;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    @Transactional
    public UserResponse execute(String key,RegisterRequest request,Supplier<UserResponse> action) {
        if(key==null || key.isBlank() || key.length()>128) throw failure(422,"INVALID_ARGUMENT","Idempotency-Key is required");
        try {
            String email=request.getEmail().trim().toLowerCase(Locale.ROOT), display=request.getDisplayName().trim(), role=request.getRole().trim().toUpperCase(Locale.ROOT);
            if(role.equals("CUSTOMER")) role="PASSENGER";
            // Never persist a fast password-verification oracle in the request digest.
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(List.of(email,display,role))));
            int inserted=jdbc.update("INSERT INTO registration_request(key,request_hash,password_hash,expires_at) VALUES(?,?,?,now()+interval '7 days') ON CONFLICT DO NOTHING",key,hash,passwords.encode(request.getPassword()));
            if(inserted==0) {
                var row=jdbc.queryForMap("SELECT request_hash,password_hash,response_body FROM registration_request WHERE key=?",key);
                UserResponse cached=mapper.readValue((String)row.get("response_body"),UserResponse.class);
                boolean metadata=hash.equals(row.get("request_hash")) || ("".equals(row.get("request_hash")) && email.equals(cached.getEmail()) && display.equals(cached.getDisplayName()) && cached.getRoles().contains(role));
                if(!metadata || !(row.get("password_hash") instanceof String passwordHash) || !passwords.matches(request.getPassword(),passwordHash)) throw failure(409,"IDEMPOTENCY_CONFLICT","Key already used with a different request");
                if("".equals(row.get("request_hash"))) jdbc.update("UPDATE registration_request SET request_hash=? WHERE key=?",hash,key);
                return cached;
            }
            UserResponse result=action.get();
            jdbc.update("UPDATE registration_request SET response_body=? WHERE key=?",mapper.writeValueAsString(result),key);
            return result;
        } catch(RuntimeException e) { throw e; }
        catch(Exception e) { throw new IllegalStateException("Registration idempotency failed",e); }
    }
    private BaseException failure(int status,String code,String message) { return new BaseException(status,code,message) {}; }
}
