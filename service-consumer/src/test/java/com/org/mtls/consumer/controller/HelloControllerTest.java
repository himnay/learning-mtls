package com.org.mtls.consumer.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.org.mtls.consumer.client.ProducerClient;
import com.org.mtls.consumer.client.ProducerClient.Greeting;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HelloController.class)
class HelloControllerTest {

    private static final Request UPSTREAM_REQUEST = Request.create(
            Request.HttpMethod.GET, "https://localhost:8443/api/v1/greetings/mtls", Map.of(), null, StandardCharsets.UTF_8, null);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProducerClient producerClient;

    @Test
    void wrapsUpstreamGreeting() throws Exception {
        given(producerClient.fetchGreeting("mtls", "de"))
                .willReturn(new Greeting("Hallo, mtls!", "de", "service-producer", "service-consumer", Instant.now()));

        mockMvc.perform(get("/api/v1/hello/mtls").param("lang", "de"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumer").value("service-consumer"))
                .andExpect(jsonPath("$.upstream.message").value("Hallo, mtls!"))
                .andExpect(jsonPath("$.upstream.callerCn").value("service-consumer"));
    }

    @Test
    void handshakeFailureBecomesBadGateway() throws Exception {
        given(producerClient.fetchGreeting("mtls", "en")).willThrow(new RetryableException(
                -1, "PKIX path building failed", Request.HttpMethod.GET,
                new SSLHandshakeException("PKIX path building failed"), (Long) null, UPSTREAM_REQUEST));

        mockMvc.perform(get("/api/v1/hello/mtls"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("service-producer unreachable or TLS handshake failed"));
    }

    @Test
    void upstreamNotFoundPassesThrough() throws Exception {
        given(producerClient.fetchGreeting("mtls", "xx"))
                .willThrow(new FeignException.NotFound("Not Found", UPSTREAM_REQUEST, null, Map.of()));

        mockMvc.perform(get("/api/v1/hello/mtls").param("lang", "xx"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Greeting not found upstream"));
    }

    @Test
    void upstreamForbiddenBecomesBadGateway() throws Exception {
        given(producerClient.fetchGreeting("mtls", "en"))
                .willThrow(new FeignException.Forbidden("Forbidden", UPSTREAM_REQUEST, null, Map.of()));

        mockMvc.perform(get("/api/v1/hello/mtls"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("service-producer responded 403"));
    }
}
