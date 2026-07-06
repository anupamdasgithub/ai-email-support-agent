package io.camunda.example.loan.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Calls the local embeddings service (sentence-transformers all-mpnet-base-v2, 768-dim).
 *
 * Endpoint default: http://embeddings-service:8000 (compose hostname); overridden to
 * http://localhost:8000 for local JVM runs via the embeddings.service.url property.
 *
 * Uses Spring's RestClient (spring-boot-starter-web is already on the classpath).
 * The JDK java.net.http.HttpClient was dropping the POST body on localhost in this
 * environment, yielding a 422 at the service; RestClient transmits it reliably.
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient rest;
    private final String baseUrl;

    public EmbeddingClient(
            @Value("${embeddings.service.url:http://embeddings-service:8000}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.rest = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** Embed a single text into a 768-dim vector. Returns null on failure (callers handle gracefully). */
    public float[] embed(String text) {
        List<float[]> out = embedBatch(List.of(text == null ? "" : text));
        return out.isEmpty() ? null : out.get(0);
    }

    /** Embed multiple texts in one call. */
    public List<float[]> embedBatch(List<String> texts) {
        try {
            // Build the request JSON explicitly so the body is unambiguous.
            StringBuilder sb = new StringBuilder();
            sb.append("{\"texts\":[");
            for (int i = 0; i < texts.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(jsonQuote(texts.get(i) == null ? "" : texts.get(i)));
            }
            sb.append("]}");
            String body = sb.toString();

            if (log.isDebugEnabled()) {
                log.debug("[embeddings] POST {}/embed bodyLen={} bodyHead={}",
                        baseUrl, body.length(),
                        body.substring(0, Math.min(120, body.length())));
            }

            String resp = rest.post()
                    .uri("/embed")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            if (resp == null || resp.isBlank()) {
                log.error("[embeddings] empty response");
                return List.of();
            }

            JsonNode root = mapper.readTree(resp);
            JsonNode embeddings = root.get("embeddings");
            if (embeddings == null || !embeddings.isArray()) {
                log.error("[embeddings] malformed response: {}", resp);
                return List.of();
            }

            List<float[]> result = new ArrayList<>(embeddings.size());
            for (JsonNode vecNode : embeddings) {
                float[] vec = new float[vecNode.size()];
                for (int i = 0; i < vec.length; i++) {
                    vec[i] = (float) vecNode.get(i).asDouble();
                }
                result.add(vec);
            }
            return result;

        } catch (Exception e) {
            log.error("[embeddings] call failed", e);
            return List.of();
        }
    }

    /** Minimal JSON string escaping for manual body construction. */
    private static String jsonQuote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
