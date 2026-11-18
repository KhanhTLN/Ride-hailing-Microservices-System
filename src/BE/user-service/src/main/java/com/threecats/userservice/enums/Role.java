package com.threecats.userservice.enums;

public enum Role {
    PASSENGER,
    DRIVER,
    ADMIN;

    public static Role fromString(String role) {
        if (role == null || role.isBlank()) {
            return PASSENGER;
        }
        if ("CUSTOMER".equalsIgnoreCase(role)) {
            return PASSENGER;
        }
        return Role.valueOf(role.trim().toUpperCase());
    }
}
