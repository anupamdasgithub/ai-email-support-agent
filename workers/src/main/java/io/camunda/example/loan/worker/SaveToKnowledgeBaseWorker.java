package io.camunda.example.loan.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.example.loan.rag.EmbeddingClient;
import io.camunda.example.loan.rag.EsVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Job type: save-to-knowledge-base
 *
 * BPMN contract (from ioMapping):
 *   input  documentText  = toolResult   (the answer text to store)
 *   input  indexName     = "specialist-knowledge-base"
 *
 * Embeds documentText and indexes {text, embedding} into indexName.
 */
@Component
public class SaveToKnowledgeBaseWorker {

    private static final Logger log = LoggerFactory.getLogger(SaveToKnowledgeBaseWorker.class);

    private final EmbeddingClient embeddings;
    private final EsVectorStore store;

    public SaveToKnowledgeBaseWorker(EmbeddingClient embeddings, EsVectorStore store) {
        this.embeddings = embeddings;
        this.store = store;
    }

    @JobWorker(type = "save-to-knowledge-base")
    public void saveToKnowledgeBase(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String documentText = str(vars.get("documentText"));
        String indexName = str(vars.getOrDefault("indexName", "specialist-knowledge-base"));

        log.info("[save-to-knowledge-base] index={} textLen={}", indexName, documentText.length());

        if (documentText.isBlank()) {
            log.warn("[save-to-knowledge-base] empty documentText, nothing to store");
            return;
        }

        float[] vec = embeddings.embed(documentText);
        if (vec == null) {
            log.error("[save-to-knowledge-base] embedding failed, skipping store");
            return;
        }

        String id = store.index(indexName, documentText, vec, null);
        log.info("[save-to-knowledge-base] stored id={}", id);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
