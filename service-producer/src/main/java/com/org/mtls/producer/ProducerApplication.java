package com.org.mtls.producer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ProducerApplication {

    static void main(String[] args) {
        SpringApplication.run(ProducerApplication.class, args);
    }
}
