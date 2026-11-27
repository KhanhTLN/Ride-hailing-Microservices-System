package com.threecats.pricingservice.maps;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(MapsProperties.class)
public class MapsConfiguration {
    @Bean public HttpClient mapsHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Bean public ObjectMapper mapsObjectMapper() { return new ObjectMapper().findAndRegisterModules(); }
}
