package com.threecats.common.dto.response;

public record Ack(boolean success) {
    public static Ack of(boolean success) {
        return new Ack(success);
    }
}
