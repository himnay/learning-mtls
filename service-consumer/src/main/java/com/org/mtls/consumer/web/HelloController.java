package com.org.mtls.consumer.web;

import com.org.mtls.consumer.client.ProducerClient;
import com.org.mtls.consumer.client.ProducerClient.Greeting;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hello")
@RequiredArgsConstructor
public class HelloController {

    private final ProducerClient producerClient;

    @GetMapping("/{name}")
    public HelloResponse hello(@PathVariable String name, @RequestParam(defaultValue = "en") String lang) {
        return new HelloResponse("service-consumer", producerClient.fetchGreeting(name, lang));
    }

    public record HelloResponse(String consumer, Greeting upstream) {}
}
