package io.camunda.example.loan.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.example.loan.rag.EmbeddingClient;
import io.camunda.example.loan.rag.EsVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Job type: query-knowledge-base   (AI Agent tool)
 *
 * BPMN contract (from ioMapping):
 *   input  query      = fromAi(toolCall.query, ...)
 *   input  indexName  = "specialist-knowledge-base"
 *   output feeds the agent as toolResult; boundary event "knowledge base empty"
 *          + gateway branch on knowledgeBaseDecision ("yes"/"no")
 *
 * Embeds the query, runs kNN over the index, returns the retrieved text.
 * Sets knowledgeBaseDecision = "yes" when hits found, "no" when empty.
 */
@Component
public class QueryKnowledgeBaseWorker {

    private static final Logger log = LoggerFactory.getLogger(QueryKnowledgeBaseWorker.class);

    private static final int TOP_K = 3;
    private static final double MIN_SCORE = 0.3; // cosine floor; below this we treat as "not relevant"

    private final EmbeddingClient embeddings;
    private final EsVectorStore store;

    public QueryKnowledgeBaseWorker(EmbeddingClient embeddings, EsVectorStore store) {
        this.embeddings = embeddings;
        this.store = store;
    }

    @JobWorker(type = "query-knowledge-base")
    public Map<String, Object> queryKnowledgeBase(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String query = str(vars.get("query"));
        String indexName = str(vars.getOrDefault("indexName", "specialist-knowledge-base"));

        log.info("[query-knowledge-base] index={} query='{}'", indexName, query);

        Map<String, Object> out = new HashMap<>();

        if (query.isBlank()) {
            out.put("knowledgeBaseResult", "No query provided.");
            out.put("knowledgeBaseDecision", "no");
            out.put("toolResult", "No query provided.");
            return out;
        }

        float[] qv = embeddings.embed(query);
        List<EsVectorStore.Chunk> hits = (qv == null)
                ? List.of()
                : store.knnSearch(indexName, qv, TOP_K).stream()
                    .filter(c -> c.score() >= MIN_SCORE)
                    .collect(Collectors.toList());

        if (hits.isEmpty()) {
            log.info("[query-knowledge-base] no relevant hits (decision=no)");
            out.put("knowledgeBaseResult", "No relevant knowledge found.");
            out.put("knowledgeBaseDecision", "no");
            out.put("toolResult", "No relevant knowledge found.");
            return out;
        }

        String joined = hits.stream()
                .map(c -> "- " + c.text() + "  (score=" + String.format("%.3f", c.score()) + ")")
                .collect(Collectors.joining("\n"));

        log.info("[query-knowledge-base] {} hit(s), topScore={} (decision=yes)",
                hits.size(), String.format("%.3f", hits.get(0).score()));

        out.put("knowledgeBaseResult", joined);
        out.put("knowledgeBaseDecision", "yes");
        out.put("toolResult", joined);
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
