package com.ka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KaApplication {

    public static void main(String[] args) {
        SpringApplication.run(KaApplication.class, args);
    }
}
