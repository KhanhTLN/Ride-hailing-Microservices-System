package com.threecats.common.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Validates User Service access tokens; identity never comes from X-User-Id. */
public class UserJwtFilter extends OncePerRequestFilter {
    private final JwtParser parser;
    public UserJwtFilter(String secret) {
        parser=Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .requireIssuer("identity-service").requireAudience("ds03-api").build();
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String header=request.getHeader("Authorization");
        if (header!=null) {
            try {
                if (!header.startsWith("Bearer ")) throw new IllegalArgumentException();
                Claims claims=parser.parseSignedClaims(header.substring(7)).getPayload();
                UUID.fromString(claims.getSubject());
                if (!"access".equals(claims.get("token_use"))) throw new IllegalArgumentException();
                Object roles=claims.get("roles");
                if (!(roles instanceof List<?> list)) throw new IllegalArgumentException();
                var authorities=list.stream().map(r -> new SimpleGrantedAuthority("ROLE_"+r)).toList();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(claims.getSubject(),null,authorities));
            } catch (RuntimeException e) {
                SecurityContextHolder.clearContext(); failure(response,401,"UNAUTHENTICATED"); return;
            }
        }
        chain.doFilter(request,response);
    }
    public static void failure(HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.getWriter().write("{\"code\":\""+code+"\",\"message\":\""+code+"\",\"correlation_id\":\""+com.threecats.common.context.CorrelationContext.get()+"\",\"retryable\":false,\"details\":{}}");
    }
}
