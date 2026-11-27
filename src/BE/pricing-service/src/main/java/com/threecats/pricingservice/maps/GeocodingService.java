package com.threecats.pricingservice.maps;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service @RequiredArgsConstructor
public class GeocodingService {
    private final ProviderHttpClient http;
    private final MapsProperties properties;
    public record Place(String place_id,String display_name,double latitude,double longitude) {}
    public List<Place> search(String query) {
        if(query==null || query.strip().length()<3 || query.length()>200) throw new MapsException(422,"INVALID_ARGUMENT","Địa chỉ tìm kiếm cần từ 3 đến 200 ký tự.");
        String q=query.strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
        String suffix="/search?format=jsonv2&limit=5&addressdetails=0&q="+encode(q)+"&accept-language="+encode(properties.getLanguage());
        if(!properties.getCountryCodes().isBlank()) suffix+="&countrycodes="+encode(properties.getCountryCodes());
        return http.get("nominatim",ProviderHttpClient.endpoint(properties.getNominatimBaseUrl(),suffix),properties.getGeocodeCacheSeconds(),root -> {
            if(!root.isArray()) throw MapsException.unavailable();
            List<Place> places=new ArrayList<>();
            for(JsonNode node:root) { if(places.size()==5) break; places.add(place(node)); }
            return places;
        });
    }
    public Place reverse(double latitude,double longitude) {
        validatePoint(latitude,longitude);
        String suffix=String.format(Locale.ROOT,"/reverse?format=jsonv2&lat=%.7f&lon=%.7f&zoom=18&addressdetails=0&accept-language=%s",latitude,longitude,encode(properties.getLanguage()));
        return http.get("nominatim",ProviderHttpClient.endpoint(properties.getNominatimBaseUrl(),suffix),properties.getGeocodeCacheSeconds(),this::place);
    }
    private Place place(JsonNode node) {
        try {
            double latitude=Double.parseDouble(node.path("lat").asText()), longitude=Double.parseDouble(node.path("lon").asText());
            if(!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude)>90 || Math.abs(longitude)>180) throw MapsException.unavailable();
            String name=node.path("display_name").asText();
            if(name.isBlank()) throw MapsException.unavailable();
            return new Place(node.path("place_id").asText(),name.length()>300 ? name.substring(0,300) : name,latitude,longitude);
        } catch(NumberFormatException e) { throw MapsException.unavailable(); }
    }
    static void validatePoint(double latitude,double longitude) {
        if(!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude)>90 || Math.abs(longitude)>180)
            throw new MapsException(422,"INVALID_ARGUMENT","Tọa độ không hợp lệ.");
    }
    private String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
}
