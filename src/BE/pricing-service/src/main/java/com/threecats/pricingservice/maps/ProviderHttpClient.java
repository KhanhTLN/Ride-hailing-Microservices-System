package com.threecats.pricingservice.maps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Function;

@Component @RequiredArgsConstructor
public class ProviderHttpClient {
    private final HttpClient mapsHttpClient;
    private final ObjectMapper mapsObjectMapper;
    private final ProviderCache cache;
    private final MapsProperties properties;
    public <T> T get(String provider,URI uri,int ttl,Function<JsonNode,T> decode) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Provider call must not run inside a database transaction");
        String key;
        try { key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        String body=cache.load(provider,key,properties.getMinimumIntervalMs(),ttl,() -> {
            String value=send(provider,uri);
            decode.apply(parse(value)); // Invalid upstream payloads never enter the cache.
            return value;
        });
        return decode.apply(parse(body));
    }
    private String send(String provider,URI uri) {
        try {
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(Math.max(1,properties.getTimeoutSeconds())))
                .header("User-Agent",properties.getUserAgent()).header("Accept","application/json").GET().build();
            // Bound the entire response, including a provider that stalls after headers.
            var pending=mapsHttpClient.sendAsync(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> response;
            try { response=pending.get(Math.max(1,properties.getTimeoutSeconds()),java.util.concurrent.TimeUnit.SECONDS); }
            finally { if(!pending.isDone()) pending.cancel(true); }
            String body=response.body();
            if(body.length()>2_000_000) throw MapsException.unavailable();
            if(response.statusCode()==429) throw MapsException.limited();
            if(response.statusCode()>=400 && response.statusCode()<500 && provider.equals("osrm")) {
                String code=parse(body).path("code").asText();
                if(code.equals("NoRoute") || code.equals("NoSegment")) throw noRoute();
            }
            if(response.statusCode()==404 && provider.equals("nominatim")) throw new MapsException(404,"ADDRESS_NOT_FOUND","Không tìm thấy địa chỉ cho tọa độ này.");
            if(response.statusCode()!=200) throw MapsException.unavailable();
            JsonNode json=parse(body);
            if(provider.equals("osrm") && !json.path("code").asText().equals("Ok")) {
                if(json.path("code").asText().equals("NoRoute") || json.path("code").asText().equals("NoSegment")) throw noRoute();
                throw MapsException.unavailable();
            }
            if(json.has("error")) throw new MapsException(404,"ADDRESS_NOT_FOUND","Không tìm thấy địa chỉ cho tọa độ này.");
            return body;
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw MapsException.unavailable(); }
        catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) { throw MapsException.unavailable(); }
    }
    private JsonNode parse(String body) {
        try { return mapsObjectMapper.readTree(body); }
        catch(Exception e) { throw MapsException.unavailable(); }
    }
    private MapsException noRoute() { return new MapsException(422,"ROUTE_NOT_FOUND","Không có tuyến đường phù hợp gần các điểm đã chọn."); }
    public static URI endpoint(String base,String suffix) {
        URI uri=URI.create(base.replaceAll("/+$","")+suffix);
        if(!java.util.Set.of("https","http").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)
            throw new IllegalStateException("Map provider URL must be an HTTP(S) URL without embedded credentials");
        return uri;
    }
}
