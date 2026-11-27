package com.threecats.pricingservice.maps;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter @Setter
@ConfigurationProperties(prefix="maps")
public class MapsProperties {
    private String nominatimBaseUrl="https://nominatim.openstreetmap.org";
    private String photonBaseUrl="https://photon.komoot.io";
    private String osrmBaseUrl="https://router.project-osrm.org";
    private String motorbikeOsrmBaseUrl="https://router.project-osrm.org";
    private String carProfile="driving";
    private String motorbikeProfile="driving";
    private boolean motorbikeUsesDrivingModel=true;
    private String userAgent="RideHailingMicroservicesCourse/1.0";
    private String countryCodes="vn";
    private String language="vi,en";
    private int timeoutSeconds=8;
    private int minimumIntervalMs=1100;
    private int geocodeCacheSeconds=604800;
    private int routeCacheSeconds=3600;
    private int snapRadiusM=1000;
    private String authBaseUrl="http://localhost:8082";
    private String tileUrl="https://tile.openstreetmap.org/{z}/{x}/{y}.png";
}
