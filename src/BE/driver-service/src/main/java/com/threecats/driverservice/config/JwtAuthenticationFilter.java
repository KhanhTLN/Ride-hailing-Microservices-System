package com.threecats.driverservice.config;
import com.threecats.common.security.UserJwtFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class JwtAuthenticationFilter extends UserJwtFilter {
    public JwtAuthenticationFilter(@Value("${jwt.secret}") String secret) { super(secret); }
}
