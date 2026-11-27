package com.threecats.pricingservice.maps;

import com.threecats.pricingservice.dto.QuoteInputRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Controller @RequiredArgsConstructor
public class MapsController {
    private final GeocodingService geocoding;
    private final AutocompleteService autocomplete;
    private final RoutingService routing;
    private final MapsProperties properties;
    @GetMapping({"/maps","/maps/"}) public String page() { return "forward:/maps/index.html"; }
    @GetMapping("/maps/config") @ResponseBody public Map<String,Object> config() {
        return Map.of("auth_base_url",properties.getAuthBaseUrl(),"tile_url",properties.getTileUrl(),"country_codes",properties.getCountryCodes(),
            "motorbike_uses_driving_model",properties.isMotorbikeUsesDrivingModel());
    }
    @GetMapping("/api/v1/maps/places") @ResponseBody
    public List<GeocodingService.Place> search(@RequestParam("q") String query) { return geocoding.search(query); }
    @GetMapping("/api/v1/maps/suggestions") @ResponseBody
    public List<GeocodingService.Place> suggestions(@RequestParam("q") String query) { return autocomplete.suggest(query); }
    @GetMapping("/api/v1/maps/reverse") @ResponseBody
    public GeocodingService.Place reverse(@RequestParam double latitude,@RequestParam double longitude) { return geocoding.reverse(latitude,longitude); }
    @PostMapping("/api/v1/maps/route") @ResponseBody
    public RoutingService.Route route(@Valid @RequestBody QuoteInputRequest request) { return routing.route(request.getPickup(),request.getDropoff(),request.getVehicle_type()); }
}
