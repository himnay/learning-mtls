package com.org.mtls.producer.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Optional;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Layer-7 authorization on top of mTLS. Tomcat has already rejected any client that did not
 * present a certificate signed by a trusted CA ({@code server.ssl.client-auth=need}); this
 * filter additionally checks that the certificate identifies an allow-listed caller.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClientCertificateFilter extends OncePerRequestFilter {

    public static final String CLIENT_CN_ATTRIBUTE = "mtls.client.cn";
    private static final String X509_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private final MtlsProperties properties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> clientCn = extractClientCn(request);

        if (clientCn.isEmpty() || !properties.allowedClientCns().contains(clientCn.get())) {
            log.warn("Rejected client certificate CN={} for {}", clientCn.orElse("<none>"), request.getRequestURI());
            response.sendError(HttpStatus.FORBIDDEN.value(), "Client certificate not authorized");
            return;
        }

        request.setAttribute(CLIENT_CN_ATTRIBUTE, clientCn.get());
        chain.doFilter(request, response);
    }

    private static Optional<String> extractClientCn(HttpServletRequest request) {
        if (!(request.getAttribute(X509_ATTRIBUTE) instanceof X509Certificate[] chain) || chain.length == 0) {
            return Optional.empty();
        }
        try {
            return new LdapName(chain[0].getSubjectX500Principal().getName())
                    .getRdns().stream()
                            .filter(rdn -> "CN".equalsIgnoreCase(rdn.getType()))
                            .map(rdn -> rdn.getValue().toString())
                            .findFirst();
        } catch (InvalidNameException e) {
            return Optional.empty();
        }
    }
}
