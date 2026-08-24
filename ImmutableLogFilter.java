import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Component
public class ImmutableLogFilter extends OncePerRequestFilter {

    @Value("${immutablelog.api-key}")
    private String apiKey;

    @Value("${immutablelog.service-name:my-service}")
    private String serviceName;

    @Value("${immutablelog.env:production}")
    private String env;

    @Value("${immutablelog.url:https://api.immutablelog.com}")
    private String apiUrl;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    private static final Set<String> SKIP_PATHS = Set.of(
        "/health", "/actuator/health", "/ping"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SKIP_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {

        var wrappedReq = new ContentCachingRequestWrapper(request);
        var wrappedRes = new ContentCachingResponseWrapper(response);
        long startMs = System.currentTimeMillis();
        Throwable caught = null;

        try {
            chain.doFilter(wrappedReq, wrappedRes);
        } catch (Exception ex) {
            caught = ex;
            throw ex;
        } finally {
            long latencyMs = System.currentTimeMillis() - startMs;
            emit(wrappedReq, wrappedRes, latencyMs, caught);
            wrappedRes.copyBodyToResponse();
        }
    }

    private void emit(
        ContentCachingRequestWrapper req,
        ContentCachingResponseWrapper res,
        long latencyMs,
        Throwable ex
    ) {
        try {
            int status = res.getStatus();
            String method = req.getMethod();
            String path = req.getRequestURI();
            String requestId = UUID.randomUUID().toString();

            // Custom event name via request attribute
            String customEvent = (String) req.getAttribute("imtbl.eventName");
            String eventName = customEvent != null
                ? customEvent
                : method.toLowerCase() + "." + path.replace("/", ".").replaceAll("^\\.+", "");

            String type = status >= 500 ? "error"
                : status >= 400 ? "error"
                : status >= 300 ? "info"
                : "success";

            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("method", method);
            payloadMap.put("path", path);
            payloadMap.put("status", status);
            payloadMap.put("latency_ms", latencyMs);
            payloadMap.put("client_ip", getClientIp(req));
            payloadMap.put("user_agent", req.getHeader("User-Agent"));

            byte[] body = req.getContentAsByteArray();
            if (body.length > 0) {
                payloadMap.put("request_body_hash", sha256Hex(body));
            }

            if (ex != null) {
                Map<String, Object> errMap = new LinkedHashMap<>();
                errMap.put("type", ex.getClass().getName());
                errMap.put("message", ex.getMessage());
                errMap.put("retryable", !(ex instanceof IllegalArgumentException));
                payloadMap.put("error", errMap);
            }

            // Besides the middleware's own keys, this carries the reserved observability keys
            // of the contract (service, environment, client_ip, http_method, http_status,
            // http_route, log_level, error_type, error_message). All of them are strings:
            // the core only accepts meta as a string -> string map.
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("type", type);
            meta.put("event_name", eventName);
            meta.put("service", serviceName);
            meta.put("request_id", requestId);
            // "env" is the legacy name, kept because there may be clients in production
            // reading it; "environment" is the canonical name of the contract. Same value.
            meta.put("env", env);
            meta.put("environment", env);
            meta.put("http_method", method);
            meta.put("http_status", String.valueOf(status));
            meta.put("http_route", getHttpRoute(req, path));
            meta.put("log_level", logLevelFrom(type));

            // client_ip has to travel in meta (not only in the payload) so the platform can
            // promote it to source.ip; "unknown" is not an IP, so it is not worth sending.
            String clientIp = getClientIp(req);
            if (clientIp != null && !clientIp.isBlank() && !clientIp.equals("unknown")) {
                meta.put("client_ip", clientIp);
            }

            if (ex != null) {
                meta.put("error_type", ex.getClass().getName());
                String errMessage = ex.getMessage();
                if (errMessage != null && !errMessage.isBlank()) {
                    meta.put("error_message", truncate(errMessage, 500));
                }
            }

            Map<String, Object> body2 = Map.of(
                "payload", mapper.writeValueAsString(payloadMap),
                "meta", meta
            );

            String json = mapper.writeValueAsString(body2);

            var httpReq = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/v1/events"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("Idempotency-Key", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            // Fire and forget — never blocks the response
            http.sendAsync(httpReq, HttpResponse.BodyHandlers.discarding());

        } catch (Exception ignored) {
            // Never let audit logging break the application
        }
    }

    /**
     * Route for meta.http_route. Prefers the template resolved by Spring MVC
     * (e.g. "/api/orders/{id}") over the concrete path (e.g. "/api/orders/42"): the
     * concrete path carries the id and blows up the cardinality of the field on the
     * platform side. Falls back to the path when no handler matched (e.g. a 404 that
     * never reached the handler mapping).
     */
    private String getHttpRoute(HttpServletRequest req, String path) {
        // String with AntPathMatcher, PathPattern with PathPatternParser — toString() covers both.
        Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern != null) {
            String route = pattern.toString();
            if (!route.isBlank()) return route.startsWith("/") ? route : "/" + route;
        }
        return path;
    }

    /** Maps the event type to meta.log_level (ECS log.level). */
    private String logLevelFrom(String kind) {
        return "error".equals(kind) ? "error"
            : "warning".equals(kind) ? "warn"
            : "debug".equals(kind) ? "debug"
            : "info"; // info, success, audit
    }

    private String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private String getClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String xri = req.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri;
        return req.getRemoteAddr();
    }

    private String sha256Hex(byte[] data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "hash-error";
        }
    }
}
