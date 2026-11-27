package com.threecats.pricingservice.config;
import com.threecats.common.security.UserJwtFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
@Configuration
public class SecurityConfig {
    @Bean public SecurityFilterChain filterChain(HttpSecurity http,@Value("${jwt.secret}") String secret) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/internal/**","/error","/maps","/maps/**").permitAll().anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> UserJwtFilter.failure(res,401,"UNAUTHENTICATED"))
                .accessDeniedHandler((req,res,ex) -> UserJwtFilter.failure(res,403,"FORBIDDEN")))
            .addFilterBefore(new UserJwtFilter(secret),UsernamePasswordAuthenticationFilter.class).build();
    }
}
