package com.org.mtls.consumer.controller;

import com.org.mtls.consumer.client.ProducerClient;
import com.org.mtls.consumer.dto.HelloResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/hello")
public class HelloController {

    private final ProducerClient producerClient;

    @GetMapping("/{name}")
    public HelloResponse hello(@PathVariable String name, @RequestParam(defaultValue = "en") String lang) {
        return new HelloResponse("service-consumer", producerClient.fetchGreeting(name, lang));
    }
}
