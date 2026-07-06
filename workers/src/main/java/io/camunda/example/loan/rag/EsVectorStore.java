package io.camunda.example.loan.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over the official Elasticsearch Java client for the RAG pipeline.
 *
 * Document schema (matches the index mapping we created):
 *   text        : the stored chunk
 *   embedding   : dense_vector[768], cosine
 *   customerId  : keyword
 *   createdAt   : date
 *
 * ES has security disabled locally (xpack.security.enabled=false), so no auth.
 * Host default: elasticsearch:9200 (compose service hostname).
 */
@Component
public class EsVectorStore {

    private static final Logger log = LoggerFactory.getLogger(EsVectorStore.class);

    private final RestClient restClient;
    private final ElasticsearchTransport transport;
    private final ElasticsearchClient es;

    public EsVectorStore(
            @Value("${elasticsearch.host:elasticsearch}") String host,
            @Value("${elasticsearch.port:9200}") int port,
            @Value("${elasticsearch.scheme:http}") String scheme) {
        this.restClient = RestClient.builder(new HttpHost(host, port, scheme)).build();
        this.transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        this.es = new ElasticsearchClient(transport);
        log.info("[es] vector store initialized at {}://{}:{}", scheme, host, port);
    }

    /** A retrieved chunk: the text plus its similarity score. */
    public record Chunk(String text, double score, String customerId) {}

    /** Index one document with its embedding. Returns the ES doc id, or null on failure. */
    public String index(String indexName, String text, float[] embedding, String customerId) {
        try {
            List<Float> vec = toList(embedding);
            Map<String, Object> doc = Map.of(
                    "text", text == null ? "" : text,
                    "embedding", vec,
                    "customerId", customerId == null ? "" : customerId,
                    "createdAt", Instant.now().toString()
            );
            IndexResponse resp = es.index(i -> i.index(indexName).document(doc));
            log.info("[es] indexed id={} into {}", resp.id(), indexName);
            return resp.id();
        } catch (Exception e) {
            log.error("[es] index failed for {}", indexName, e);
            return null;
        }
    }

    /** kNN semantic search: return the top-k nearest chunks to the query vector. */
    public List<Chunk> knnSearch(String indexName, float[] queryVector, int k) {
        try {
            List<Float> qv = toList(queryVector);
            SearchResponse<Map> resp = es.search(s -> s
                            .index(indexName)
                            .knn(knn -> knn
                                    .field("embedding")
                                    .queryVector(qv)
                                    .k(k)
                                    .numCandidates(Math.max(k * 10, 50)))
                            .size(k),
                    Map.class);
            return toChunks(resp);
        } catch (Exception e) {
            // A missing index is an expected "empty" case, not an error worth failing the job.
            log.warn("[es] knnSearch on {} returned no results ({})", indexName, e.getMessage());
            return List.of();
        }
    }

    /** Non-semantic: most recent documents for a given customerId (used by fetch-past-conversations). */
    public List<Chunk> recentByCustomer(String indexName, String customerId, int k) {
        try {
            SearchResponse<Map> resp = es.search(s -> s
                            .index(indexName)
                            .query(Query.of(q -> q.term(t -> t.field("customerId").value(customerId))))
                            .sort(so -> so.field(f -> f.field("createdAt").order(
                                    co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                            .size(k),
                    Map.class);
            return toChunks(resp);
        } catch (Exception e) {
            log.warn("[es] recentByCustomer on {} returned no results ({})", indexName, e.getMessage());
            return List.of();
        }
    }

    private List<Chunk> toChunks(SearchResponse<Map> resp) {
        List<Chunk> chunks = new ArrayList<>();
        for (Hit<Map> hit : resp.hits().hits()) {
            Map<?, ?> src = hit.source();
            if (src == null) continue;
            Object text = src.get("text");
            Object cust = src.get("customerId");
            double score = hit.score() == null ? 0.0 : hit.score();
            chunks.add(new Chunk(
                    text == null ? "" : text.toString(),
                    score,
                    cust == null ? "" : cust.toString()));
        }
        return chunks;
    }

    private List<Float> toList(float[] arr) {
        List<Float> list = new ArrayList<>(arr == null ? 0 : arr.length);
        if (arr != null) for (float v : arr) list.add(v);
        return list;
    }

    @PreDestroy
    public void close() {
        try {
            transport.close();
            restClient.close();
        } catch (IOException e) {
            log.warn("[es] error closing client", e);
        }
    }
}
