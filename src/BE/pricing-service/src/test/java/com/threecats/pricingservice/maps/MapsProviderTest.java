package com.threecats.pricingservice.maps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.threecats.pricingservice.dto.PointDto;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MapsProviderTest {
    HttpServer server;
    MapsProperties properties;
    RoutingService routing;
    GeocodingService geocoding;
    AutocompleteService autocomplete;
    AtomicInteger calls;
    AtomicReference<String> uri,agent;
    String payload;
    int status;
    long delay;
    static final String ROUTE="""
      {"code":"Ok","routes":[{"distance":4321.2,"duration":912.3,"geometry":{"type":"LineString","coordinates":[[105.85,21.02],[105.86,21.03]]}}]}
      """;
    @BeforeEach void setup() throws Exception {
        payload=ROUTE; status=200; delay=0; calls=new AtomicInteger(); uri=new AtomicReference<>(); agent=new AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            calls.incrementAndGet(); uri.set(exchange.getRequestURI().toString()); agent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length);
            try { if(delay>0) Thread.sleep(delay); exchange.getResponseBody().write(bytes); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        properties=new MapsProperties();
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        properties.setOsrmBaseUrl(base); properties.setMotorbikeOsrmBaseUrl(base); properties.setNominatimBaseUrl(base); properties.setPhotonBaseUrl(base);
        Map<String,String> cache=new HashMap<>();
        ProviderCache storage=(provider,key,interval,ttl,fetch) -> cache.computeIfAbsent(key,k -> fetch.get());
        var http=new ProviderHttpClient(HttpClient.newHttpClient(),new ObjectMapper(),storage,properties);
        routing=new RoutingService(http,properties); geocoding=new GeocodingService(http,properties);
        autocomplete=new AutocompleteService(http,properties);
    }
    @AfterEach void close() { server.stop(0); }
    static PointDto point(double latitude,double longitude) { var p=new PointDto(); p.setLatitude(latitude); p.setLongitude(longitude); return p; }
    @Test void roadMetricsGeometryCoordinateOrderAndCache() {
        var a=point(21.02,105.85); var b=point(21.03,105.86);
        var result=routing.route(a,b,"CAR");
        assertEquals(4322,result.distance_m()); assertEquals(913,result.duration_s());
        assertEquals("LineString",result.geometry().get("type"));
        assertEquals(List.of(List.of(105.85,21.02),List.of(105.86,21.03)),result.geometry().get("coordinates"));
        assertTrue(uri.get().contains("/route/v1/driving/105.8500000,21.0200000;105.8600000,21.0300000"));
        assertEquals(properties.getUserAgent(),agent.get());
        routing.route(a,b,"MOTORBIKE"); // Same public driving graph may reuse identical geometry.
        assertEquals(1,calls.get());
        assertEquals("driving",routing.route(a,b,"MOTORBIKE").vehicle_model());
    }
    @Test void motorcycleEndpointAndProfileAreConfigurable() {
        properties.setMotorbikeProfile("motorcycle"); properties.setMotorbikeUsesDrivingModel(false);
        assertEquals("motorcycle",routing.route(point(21,105),point(22,106),"MOTORBIKE").vehicle_model());
        assertTrue(uri.get().contains("/route/v1/motorcycle/"));
    }
    @Test void noRouteIs422AndIsNotCached() {
        payload="{\"code\":\"NoRoute\"}";
        var error=assertThrows(MapsException.class,() -> routing.route(point(21,105),point(22,106),"CAR"));
        assertEquals(422,error.getStatus()); assertEquals("ROUTE_NOT_FOUND",error.getErrorCode());
        payload=ROUTE; routing.route(point(21,105),point(22,106),"CAR"); assertEquals(2,calls.get());
    }
    @Test void noSegmentHttp400Is422() {
        status=400; payload="{\"code\":\"NoSegment\"}";
        assertEquals(422,assertThrows(MapsException.class,() -> routing.route(point(21,105),point(22,106),"CAR")).getStatus());
    }
    @Test void unavailableProviderHasNoFakeDistanceFallback() {
        status=503;
        assertEquals(503,assertThrows(MapsException.class,() -> routing.route(point(21,105),point(22,106),"CAR")).getStatus());
    }
    @Test void rateLimitIsRetryable() {
        status=429;
        var error=assertThrows(MapsException.class,() -> geocoding.search("Hanoi"));
        assertEquals(429,error.getStatus()); assertTrue(error.isRetryable());
    }
    @Test void malformedGeometryAndMetricsAreRejected() {
        payload=ROUTE.replace("4321.2","-1");
        assertEquals(503,assertThrows(MapsException.class,() -> routing.route(point(21,105),point(22,106),"CAR")).getStatus());
        payload=ROUTE.replace("105.85,21.02","999,21.02");
        assertEquals(503,assertThrows(MapsException.class,() -> routing.route(point(21,105),point(22,106),"CAR")).getStatus());
    }
    @Test void searchEncodesQueryAndNormalizesCacheKey() {
        payload="[{\"place_id\":123,\"display_name\":\"Hồ Gươm\",\"lat\":\"21.02\",\"lon\":\"105.85\"}]";
        var places=geocoding.search("  Hồ  Gươm & Hanoi  ");
        assertEquals(21.02,places.getFirst().latitude()); assertEquals("Hồ Gươm",places.getFirst().display_name());
        assertTrue(uri.get().contains("%26")); assertTrue(uri.get().contains("countrycodes=vn"));
        geocoding.search("hồ gươm & hanoi"); assertEquals(1,calls.get());
    }
    @Test void reverseAndInvalidInput() {
        payload="{\"place_id\":1,\"display_name\":\"Hanoi\",\"lat\":\"21.02\",\"lon\":\"105.85\"}";
        assertEquals("Hanoi",geocoding.reverse(21.02,105.85).display_name());
        assertTrue(uri.get().contains("lat=21.0200000&lon=105.8500000"));
        assertEquals(422,assertThrows(MapsException.class,() -> geocoding.reverse(Double.NaN,105)).getStatus());
        assertThrows(MapsException.class,() -> geocoding.search("ab")); assertEquals(1,calls.get());
    }
    @Test void timeoutCoversStalledBodyAfterHeaders() {
        properties.setTimeoutSeconds(1); delay=2200;
        long start=System.nanoTime();
        assertEquals(503,assertThrows(MapsException.class,() -> routing.route(point(21,105),point(22,106),"CAR")).getStatus());
        assertTrue((System.nanoTime()-start)/1_000_000<2000);
    }
    @Test void autocompleteUsesPhotonAndPreservesCoordinates() {
        payload="""
            {"features":[{"geometry":{"type":"Point","coordinates":[105.85,21.02]},"properties":{"name":"Nhà hát Lớn","street":"Tràng Tiền","city":"Hà Nội","country":"Việt Nam","osm_type":"W","osm_id":123}},
            {"geometry":{"type":"Point","coordinates":[105.85,21.02]},"properties":{"name":"Nhà hát Lớn","street":"Tràng Tiền","city":"Hà Nội","country":"Việt Nam","osm_type":"W","osm_id":123}}]}
            """;
        var places=autocomplete.suggest("  Nhà hát &  Hanoi  ");
        assertEquals(1,places.size()); assertEquals(21.02,places.getFirst().latitude()); assertEquals(105.85,places.getFirst().longitude());
        assertEquals("W123",places.getFirst().place_id()); assertTrue(places.getFirst().display_name().contains("Tràng Tiền, Hà Nội"));
        assertTrue(uri.get().startsWith("/api/?")); assertTrue(uri.get().contains("countrycode=VN")); assertTrue(uri.get().contains("%26"));
        autocomplete.suggest("nhà hát & hanoi"); assertEquals(1,calls.get());
    }
    @Test void autocompleteHandlesEmptyAndInvalidInput() {
        payload="{\"features\":[]}";
        assertTrue(autocomplete.suggest("no match").isEmpty());
        assertThrows(MapsException.class,() -> autocomplete.suggest("ab"));
        assertEquals(1,calls.get());
    }
    @Test void autocompleteRejectsInvalidProviderDataWithoutNominatimFallback() {
        payload="{\"features\":[{\"geometry\":{\"type\":\"Point\",\"coordinates\":[999,21]},\"properties\":{\"name\":\"Invalid\"}}]}";
        assertEquals(503,assertThrows(MapsException.class,() -> autocomplete.suggest("Hanoi")).getStatus());
        assertEquals(1,calls.get()); assertTrue(uri.get().startsWith("/api/?"));
        payload="{\"features\":[]}"; assertTrue(autocomplete.suggest("Hanoi").isEmpty()); assertEquals(2,calls.get());
    }
}
