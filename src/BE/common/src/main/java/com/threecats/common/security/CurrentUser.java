package com.threecats.common.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import java.util.UUID;

public final class CurrentUser {
    private CurrentUser() {}
    public static UUID id() { return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName()); }
    public static boolean hasRole(String role) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth!=null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_"+role));
    }
    public static void require(String role) { if (!hasRole(role)) throw new AccessDeniedException("Role "+role+" is required"); }
}
