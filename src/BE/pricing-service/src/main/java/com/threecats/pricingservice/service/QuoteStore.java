package com.threecats.pricingservice.service;

import com.threecats.pricingservice.model.QuoteRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class QuoteStore {
    private final Map<String, QuoteRecord> quotesById = new ConcurrentHashMap<>();
    private final Map<String, String> quoteIdByIdempotencyKey = new ConcurrentHashMap<>();

    public void save(String idempotencyKey, QuoteRecord quote) {
        quotesById.put(quote.getId(), quote);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            quoteIdByIdempotencyKey.put(idempotencyKey, quote.getId());
        }
    }

    public Optional<QuoteRecord> findById(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(quotesById.get(id));
    }

    public Optional<QuoteRecord> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        String quoteId = quoteIdByIdempotencyKey.get(idempotencyKey);
        if (quoteId != null) {
            return findById(quoteId);
        }
        return Optional.empty();
    }
}
