package com.orderflow.proxy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Set;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class ReverseProxyFilter extends OncePerRequestFilter {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Value("${services.order-service.url:http://localhost:8081}")
    private String orderServiceUrl;

    @Value("${services.payment-service.url:http://localhost:8082}")
    private String paymentServiceUrl;

    @Value("${services.delivery-service.url:http://localhost:8083}")
    private String deliveryServiceUrl;

    @Value("${services.notification-service.url:http://localhost:8084}")
    private String notificationServiceUrl;

    @Value("${services.catalog-service.url:http://localhost:8085}")
    private String catalogServiceUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "host", "content-length", "connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailers", "transfer-encoding", "upgrade"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();

        // Local Gateway Routes (Static files, telemetry stream, actuator health)
        if (!uri.startsWith("/api/") || uri.startsWith("/api/events") || uri.startsWith("/api/health") || uri.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Determine destination microservice
        String targetBaseUrl;
        String serviceName;
        if (uri.startsWith("/api/payments")) {
            targetBaseUrl = paymentServiceUrl;
            serviceName = "payment-service";
        } else if (uri.startsWith("/api/deliveries") || uri.startsWith("/api/delivery")) {
            targetBaseUrl = deliveryServiceUrl;
            serviceName = "delivery-service";
        } else if (uri.startsWith("/api/notifications")) {
            targetBaseUrl = notificationServiceUrl;
            serviceName = "notification-service";
        } else if (uri.startsWith("/api/products") || uri.startsWith("/api/catalog")) {
            targetBaseUrl = catalogServiceUrl;
            serviceName = "catalog-service";
        } else {
            targetBaseUrl = orderServiceUrl;
            serviceName = "order-service";
        }

        String queryString = request.getQueryString();
        String fullTargetUrl = targetBaseUrl + uri + (queryString != null ? "?" + queryString : "");

        log.debug("Proxying [{}] {} -> {}", request.getMethod(), uri, fullTargetUrl);

        boolean isSseStream = uri.endsWith("/stream") || "text/event-stream".equalsIgnoreCase(request.getHeader("Accept"));

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(fullTargetUrl));

            if (!isSseStream) {
                reqBuilder.timeout(Duration.ofSeconds(3));
            }

            // Copy Request Headers
            Enumeration<String> headerNames = request.getHeaderNames();
            if (headerNames != null) {
                while (headerNames.hasMoreElements()) {
                    String name = headerNames.nextElement();
                    if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                        Enumeration<String> values = request.getHeaders(name);
                        while (values.hasMoreElements()) {
                            String value = values.nextElement();
                            try {
                                reqBuilder.header(name, value);
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    }
                }
            }

            // Copy Method and Body
            byte[] body = request.getInputStream().readAllBytes();
            String method = request.getMethod().toUpperCase();

            switch (method) {
                case "GET":
                    reqBuilder.GET();
                    break;
                case "POST":
                    reqBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
                    break;
                case "PUT":
                    reqBuilder.PUT(HttpRequest.BodyPublishers.ofByteArray(body));
                    break;
                case "DELETE":
                    reqBuilder.method("DELETE", body.length > 0 ? HttpRequest.BodyPublishers.ofByteArray(body) : HttpRequest.BodyPublishers.noBody());
                    break;
                default:
                    reqBuilder.method(method, body.length > 0 ? HttpRequest.BodyPublishers.ofByteArray(body) : HttpRequest.BodyPublishers.noBody());
                    break;
            }

            if (isSseStream) {
                // Stream live SSE events directly without buffering
                HttpResponse<java.io.InputStream> targetResponse = httpClient.send(
                        reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream()
                );
                response.setStatus(targetResponse.statusCode());
                targetResponse.headers().map().forEach((headerName, values) -> {
                    if (!HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase())) {
                        for (String value : values) {
                            response.addHeader(headerName, value);
                        }
                    }
                });
                response.setContentType("text/event-stream");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-cache");
                response.setHeader("Connection", "keep-alive");
                response.setHeader("X-Accel-Buffering", "no");

                try (java.io.InputStream is = targetResponse.body();
                     java.io.OutputStream os = response.getOutputStream()) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                        os.flush();
                        response.flushBuffer();
                    }
                }
                return;
            }

            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(serviceName);
            HttpResponse<byte[]> targetResponse = circuitBreaker.executeCallable(() ->
                    httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
            );

            // Write Status Code
            response.setStatus(targetResponse.statusCode());

            // Copy Response Headers
            targetResponse.headers().map().forEach((headerName, values) -> {
                if (!HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase())) {
                    for (String value : values) {
                        response.addHeader(headerName, value);
                    }
                }
            });

            // Write Response Body
            if (targetResponse.body() != null && targetResponse.body().length > 0) {
                response.getOutputStream().write(targetResponse.body());
            }
            response.getOutputStream().flush();

        } catch (CallNotPermittedException e) {
            log.warn("Circuit Breaker is OPEN for service [{}]. Fast-failing request to {}.", serviceName, fullTargetUrl);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Service Unavailable\", \"message\": \"Circuit breaker is OPEN for downstream " + serviceName + "\", \"status\": 503}");
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("Downstream request timed out (3s) routing to {}: {}", fullTargetUrl, e.getMessage());
            response.setStatus(HttpServletResponse.SC_GATEWAY_TIMEOUT);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Gateway Timeout\", \"message\": \"Downstream " + serviceName + " timed out\", \"status\": 504}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.setStatus(HttpServletResponse.SC_GATEWAY_TIMEOUT);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Gateway Timeout\", \"message\": \"Request execution interrupted\", \"status\": 504}");
        } catch (Exception e) {
            log.error("Proxy error routing to {}: {}", fullTargetUrl, e.getMessage());
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Bad Gateway\", \"message\": \"Downstream microservice unavailable\", \"serviceUrl\": \"" + targetBaseUrl + "\", \"status\": 502}");
        }
    }
}
