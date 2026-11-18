package com.threecats.tripservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.threecats.tripservice", "com.threecats.common"})
@EnableScheduling
@EnableAsync
public class TripServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TripServiceApplication.class, args);
    }
}
