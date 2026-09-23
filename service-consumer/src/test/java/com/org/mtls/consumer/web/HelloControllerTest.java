package com.org.mtls.consumer.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.org.mtls.consumer.client.ProducerClient;
import com.org.mtls.consumer.client.ProducerClient.Greeting;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

@WebMvcTest(HelloController.class)
class HelloControllerTest {

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
        given(producerClient.fetchGreeting("mtls", "en")).willThrow(new ResourceAccessException("PKIX path building failed"));

        mockMvc.perform(get("/api/v1/hello/mtls"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("service-producer unreachable or TLS handshake failed"));
    }
}
