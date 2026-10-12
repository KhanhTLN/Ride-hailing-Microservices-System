package com.threecats.userservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService customUserDetailsService; // Hoặc UserDetailsService của bạn
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        try {
            String token = getJwtFromRequest(request);

            // LOG 1: Kiểm tra xem Filter có lấy được Token từ Header không
            if (!StringUtils.hasText(token)) {
                log.warn("[JWT Filter] Request den {} KHONG CO Token hoac sai dinh dang Header Authorization!", request.getRequestURI());
            } else {
                log.info("[JWT Filter] Token tim thay: {}", token);

                // LOG 2: Kiểm tra kết quả Validate
                boolean isValid = jwtTokenProvider.validateToken(token);
                log.info("[JWT Filter] Ket qua Validate Token: {}", isValid);

                if (isValid) {
                    String userId = jwtTokenProvider.getUserIdFromJwt(token);
                    log.info("[JWT Filter] UserId giai ma tu Token: {}", userId);

                    UserDetails userDetails = customUserDetailsService.loadUserByID(userId);

                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities()
                    );
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    // Dòng quan trọng nhất
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.info("[JWT Filter] SET AUTHENTICATION THANH CONG cho User: {}", userDetails.getUsername());
                }
            }
        } catch (Exception ex) {
            // LOG 3: Bắt Exception nếu có lỗi xảy ra
            log.error("[JWT Filter] LOI XAY RA TRONG QUAN TRINH XAC THUC: ", ex);
        }

        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}