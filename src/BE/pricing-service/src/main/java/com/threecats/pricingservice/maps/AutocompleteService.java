package com.threecats.pricingservice.maps;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Search-as-you-type uses Photon; public Nominatim must never serve autocomplete. */
@Service @RequiredArgsConstructor
public class AutocompleteService {
    private final ProviderHttpClient http;
    private final MapsProperties properties;

    public List<GeocodingService.Place> suggest(String query) {
        if(query==null || query.strip().length()<3 || query.length()>200)
            throw new MapsException(422,"INVALID_ARGUMENT","Địa chỉ tìm kiếm cần từ 3 đến 200 ký tự.");
        String normalized=query.strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
        String suffix="/api/?q="+URLEncoder.encode(normalized,StandardCharsets.UTF_8)+"&limit=8";
        for(String country:properties.getCountryCodes().split(",")) {
            if(country.isBlank()) continue;
            if(!country.strip().matches("[a-zA-Z]{2}")) throw new IllegalStateException("Invalid geocoding country code");
            suffix+="&countrycode="+country.strip().toUpperCase(Locale.ROOT);
        }
        return http.get("photon",ProviderHttpClient.endpoint(properties.getPhotonBaseUrl(),suffix),properties.getGeocodeCacheSeconds(),this::decode);
    }
    private List<GeocodingService.Place> decode(JsonNode root) {
        JsonNode features=root.path("features");
        if(!features.isArray()) throw MapsException.unavailable();
        List<GeocodingService.Place> places=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(JsonNode feature:features) {
            if(places.size()==8) break;
            JsonNode geometry=feature.path("geometry"),coordinates=geometry.path("coordinates"),details=feature.path("properties");
            if(!geometry.path("type").asText().equals("Point") || !coordinates.isArray() || coordinates.size()<2
                || !coordinates.get(0).isNumber() || !coordinates.get(1).isNumber()) throw MapsException.unavailable();
            double lon=coordinates.get(0).asDouble(),lat=coordinates.get(1).asDouble();
            if(!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat)>90 || Math.abs(lon)>180) throw MapsException.unavailable();
            LinkedHashSet<String> parts=new LinkedHashSet<>();
            add(parts,details.path("name").asText());
            add(parts,(details.path("housenumber").asText()+" "+details.path("street").asText()).strip());
            for(String field:List.of("district","city","county","state","country")) add(parts,details.path(field).asText());
            if(parts.isEmpty()) throw MapsException.unavailable();
            String name=String.join(", ",parts);
            if(name.length()>300) name=name.substring(0,300);
            String id=details.path("osm_type").asText()+details.path("osm_id").asText();
            if(id.isBlank()) id=lat+","+lon;
            if(seen.add(name+"|"+lat+"|"+lon)) places.add(new GeocodingService.Place(id,name,lat,lon));
        }
        return places;
    }
    private void add(Set<String> parts,String value) { if(!value.isBlank()) parts.add(value.strip()); }
}
