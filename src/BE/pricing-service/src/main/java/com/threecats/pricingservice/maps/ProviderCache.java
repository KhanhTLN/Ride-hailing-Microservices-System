package com.threecats.pricingservice.maps;

import java.util.function.Supplier;

public interface ProviderCache {
    String load(String provider,String key,int intervalMs,int ttlSeconds,Supplier<String> fetch);
}
