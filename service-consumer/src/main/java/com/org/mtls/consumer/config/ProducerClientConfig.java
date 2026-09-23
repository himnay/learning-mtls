package com.org.mtls.consumer.config;

import com.org.mtls.consumer.client.ProducerClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the OpenFeign client for service-producer. Kept off the application class so
 * {@code @WebMvcTest} slices don't try to create Feign clients.
 */
@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = ProducerClient.class)
public class ProducerClientConfig {}
