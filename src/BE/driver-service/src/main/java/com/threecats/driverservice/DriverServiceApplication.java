package com.threecats.driverservice;

import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.threecats.driverservice", "com.threecats.common"})
public class DriverServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DriverServiceApplication.class, args);
    }
}
