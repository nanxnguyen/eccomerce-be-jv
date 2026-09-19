package com.example.backend.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Set;

@Component
public class HttpExchangeLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HttpExchangeLoggingFilter.class);
    private static final int BODY_LIMIT_BYTES = 8 * 1024;
    private final ObjectMapper objectMapper;
    private final boolean logBodies;

    public HttpExchangeLoggingFilter(ObjectMapper objectMapper,
                                     @Value("${app.http-logging.include-body:false}") boolean logBodies) {
        this.objectMapper = objectMapper;
        this.logBodies = logBodies;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        String account = account();
        String requestBody = "<disabled>";
        BoundedRequestWrapper cachedRequest = null;
        BoundedResponseWrapper cachedResponse = null;

        if (logBodies) {
            cachedRequest = new BoundedRequestWrapper(request);
            cachedResponse = new BoundedResponseWrapper(response);
        }

        BoundedRequestWrapper loggedRequest = cachedRequest;
        BoundedResponseWrapper loggedResponse = cachedResponse;
        HttpServletRequest requestToFilter = cachedRequest == null ? request : cachedRequest;
        HttpServletResponse responseToFilter = cachedResponse == null ? response : cachedResponse;

        try {
            filterChain.doFilter(requestToFilter, responseToFilter);
        } finally {
            if (loggedResponse != null) loggedResponse.flushWriter();
            if (loggedRequest != null) {
                requestBody = body(loggedRequest.getContentAsByteArray(), request.getContentType(), loggedRequest.overflowed());
            }
            writeLog("request", request, response.getStatus(), 0, account, requestBody, null);
            writeLog("response", request, response.getStatus(),
                    (System.nanoTime() - startedAt) / 1_000_000, account, null,
                    loggedResponse == null ? "<disabled>" : body(loggedResponse.body(), response.getContentType(), loggedResponse.overflowed()));
        }
    }

    private void writeLog(String event, HttpServletRequest request, int status, long durationMs,
                          String account, String requestBody, String responseBody) {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("event", event);
        entry.put("method", request.getMethod());
        entry.put("path", request.getRequestURI());
        entry.put("status", status);
        entry.put("account", account);
        if (event.equals("request")) entry.put("request_body", requestBody);
        else {
            entry.put("duration_ms", durationMs);
            entry.put("response_body", responseBody);
        }
        log.info("http_exchange {}", entry);
    }

    private static String account() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null
                || auth.getName().equals("anonymousUser")) return "anonymous";
        return auth.getName();
    }

    private String body(byte[] bytes, String contentType, boolean overflowed) {
        if (bytes.length == 0) return "-";
        if (contentType == null || !isJson(contentType)) return "<omitted: non-JSON>";
        if (overflowed) return "<omitted: body exceeds 8192 bytes>";

        try {
            JsonNode json = objectMapper.readTree(bytes);
            redact(json);
            return objectMapper.writeValueAsString(json);
        } catch (Exception ex) {
            return "<omitted: invalid JSON>";
        }
    }

    private static boolean isJson(String contentType) {
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.contains("application/json") || type.contains("+json");
    }

    private static void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (var field : object.properties()) {
                if (sensitive(field.getKey())) object.set(field.getKey(), StringNode.valueOf("[REDACTED]"));
                else redact(field.getValue());
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) redact(node.get(i));
        }
    }

    private static boolean sensitive(String field) {
        String key = field.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return Set.of("password", "token", "secret", "authorization", "apikey", "privatekey",
                        "cvv", "cvc", "otp", "email", "phone", "address")
                .stream().anyMatch(key::contains);
    }

    private static final class BoundedRequestWrapper extends ContentCachingRequestWrapper {
        private boolean overflowed;

        private BoundedRequestWrapper(HttpServletRequest request) {
            super(request, BODY_LIMIT_BYTES);
        }

        @Override
        protected void handleContentOverflow(int contentCacheLimit) {
            overflowed = true;
        }

        private boolean overflowed() {
            return overflowed;
        }
    }

    private static final class BoundedResponseWrapper extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream cached = new ByteArrayOutputStream(BODY_LIMIT_BYTES);
        private ServletOutputStream outputStream;
        private PrintWriter writer;
        private long bytesWritten;

        private BoundedResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (writer != null) throw new IllegalStateException("getWriter() has already been called");
            if (outputStream == null) {
                ServletOutputStream delegate = super.getOutputStream();
                outputStream = new ServletOutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        delegate.write(value);
                        cache(new byte[]{(byte) value}, 0, 1);
                    }

                    @Override
                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        delegate.write(bytes, offset, length);
                        cache(bytes, offset, length);
                    }

                    @Override
                    public boolean isReady() {
                        return delegate.isReady();
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        delegate.setWriteListener(listener);
                    }
                };
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer != null) return writer;
            if (outputStream != null) throw new IllegalStateException("getOutputStream() has already been called");
            String encoding = getCharacterEncoding();
            writer = new PrintWriter(new OutputStreamWriter(getOutputStreamUnchecked(),
                    Charset.forName(encoding == null ? "UTF-8" : encoding)));
            return writer;
        }

        private ServletOutputStream getOutputStreamUnchecked() {
            try {
                return getOutputStream();
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        private void cache(byte[] bytes, int offset, int length) {
            int remaining = BODY_LIMIT_BYTES - cached.size();
            if (remaining > 0) cached.write(bytes, offset, Math.min(length, remaining));
            bytesWritten += length;
        }

        private void flushWriter() throws IOException {
            if (writer != null) writer.flush();
        }

        private byte[] body() {
            return cached.toByteArray();
        }

        private boolean overflowed() {
            return bytesWritten > BODY_LIMIT_BYTES;
        }

        @Override
        public void resetBuffer() {
            super.resetBuffer();
            cached.reset();
            bytesWritten = 0;
        }

        @Override
        public void reset() {
            super.reset();
            cached.reset();
            bytesWritten = 0;
        }
    }
}
