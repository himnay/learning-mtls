package com.org.mtls.producer.security;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authorization rules applied after the TLS handshake has already validated the client
 * certificate chain against the truststore.
 *
 * @param allowedClientCns Common Names of client certificates allowed to call the API
 */
@ConfigurationProperties(prefix = "mtls")
public record MtlsProperties(Set<String> allowedClientCns) {

    public MtlsProperties {
        allowedClientCns = allowedClientCns == null ? Set.of() : Set.copyOf(allowedClientCns);
    }
}
