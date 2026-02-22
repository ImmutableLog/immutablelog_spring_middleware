import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
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

            Map<String, Object> meta = Map.of(
                "type", type,
                "event_name", eventName,
                "service", serviceName,
                "request_id", requestId,
                "env", env
            );

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
