package com.nomad;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.nomad.common",
        "com.nomad.reactor"
})
@EnableScheduling
public class NomadReactorApplication {
    public static void main(String[] args) {
        SpringApplication.run(NomadReactorApplication.class, args);
    }
}