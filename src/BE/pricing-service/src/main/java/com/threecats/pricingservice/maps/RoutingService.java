package com.threecats.pricingservice.maps;

import com.fasterxml.jackson.databind.JsonNode;
import com.threecats.pricingservice.dto.PointDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class RoutingService {
    private final ProviderHttpClient http;
    private final MapsProperties properties;
    public record Route(long distance_m,long duration_s,Map<String,Object> geometry,String provider,String profile,String vehicle_model) {}
    public Route route(PointDto pickup,PointDto dropoff,String vehicleType) {
        if(pickup==null || dropoff==null || pickup.getLatitude()==null || pickup.getLongitude()==null || dropoff.getLatitude()==null || dropoff.getLongitude()==null)
            throw new MapsException(422,"INVALID_ARGUMENT","Cần đủ tọa độ điểm đón và điểm đến.");
        GeocodingService.validatePoint(pickup.getLatitude(),pickup.getLongitude());
        GeocodingService.validatePoint(dropoff.getLatitude(),dropoff.getLongitude());
        if(!Set.of("CAR","MOTORBIKE").contains(vehicleType)) throw new MapsException(422,"INVALID_ARGUMENT","Loại xe không hợp lệ.");
        boolean motorbike=vehicleType.equals("MOTORBIKE");
        String base=motorbike ? properties.getMotorbikeOsrmBaseUrl() : properties.getOsrmBaseUrl();
        String profile=motorbike ? properties.getMotorbikeProfile() : properties.getCarProfile();
        if(!profile.matches("[a-zA-Z0-9_-]{1,32}")) throw new IllegalStateException("Invalid OSRM profile configuration");
        String suffix=String.format(Locale.ROOT,"/route/v1/%s/%.7f,%.7f;%.7f,%.7f?overview=full&geometries=geojson&steps=false&alternatives=false&generate_hints=false&radiuses=%d;%d",
            profile,pickup.getLongitude(),pickup.getLatitude(),dropoff.getLongitude(),dropoff.getLatitude(),properties.getSnapRadiusM(),properties.getSnapRadiusM());
        return http.get("osrm",ProviderHttpClient.endpoint(base,suffix),properties.getRouteCacheSeconds(),root -> decode(root,profile,motorbike));
    }
    private Route decode(JsonNode root,String profile,boolean motorbike) {
        var routes=root.path("routes");
        if(!routes.isArray() || routes.isEmpty()) throw new MapsException(422,"ROUTE_NOT_FOUND","Không tìm thấy tuyến đường.");
        var first=routes.get(0); var distance=first.path("distance"); var duration=first.path("duration");
        if(!distance.isNumber() || !duration.isNumber() || !validMetric(distance.asDouble()) || !validMetric(duration.asDouble())) throw MapsException.unavailable();
        JsonNode geometry=first.path("geometry"), coordinates=geometry.path("coordinates");
        if(!geometry.path("type").asText().equals("LineString") || !coordinates.isArray() || coordinates.size()<2) throw MapsException.unavailable();
        List<List<Double>> points=new ArrayList<>();
        for(JsonNode point:coordinates) {
            if(!point.isArray() || point.size()<2 || !point.get(0).isNumber() || !point.get(1).isNumber()) throw MapsException.unavailable();
            double lon=point.get(0).asDouble(),lat=point.get(1).asDouble();
            if(!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat)>90 || Math.abs(lon)>180) throw MapsException.unavailable();
            points.add(List.of(lon,lat)); // GeoJSON is longitude,latitude; Leaflet converts it.
        }
        return new Route((long)Math.ceil(distance.asDouble()),(long)Math.ceil(duration.asDouble()),Map.of("type","LineString","coordinates",points),"OSRM",profile,
            motorbike && !properties.isMotorbikeUsesDrivingModel() ? "motorcycle" : "driving");
    }
    private boolean validMetric(double value) { return Double.isFinite(value) && value>=0 && value<=Integer.MAX_VALUE; }
}
