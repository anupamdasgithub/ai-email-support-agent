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
 * Job type: save-customer-interaction
 *
 * BPMN contract (from ioMapping):
 *   input  documentText = agent.responseText
 *   input  indexName    = "conversation-knowledge-base-" + currentEmail.fromAddress
 *   input  customerId   = currentEmail.fromAddress
 *
 * Embeds the interaction and stores it in the customer's conversation index
 * (auto-created by the conversation-kb-template we registered).
 */
@Component
public class SaveConversationWorker {

    private static final Logger log = LoggerFactory.getLogger(SaveConversationWorker.class);

    private final EmbeddingClient embeddings;
    private final EsVectorStore store;

    public SaveConversationWorker(EmbeddingClient embeddings, EsVectorStore store) {
        this.embeddings = embeddings;
        this.store = store;
    }

    @JobWorker(type = "save-customer-interaction")
    public void saveCustomerInteraction(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String documentText = str(vars.get("documentText"));
        String indexName = str(vars.get("indexName"));
        String customerId = str(vars.get("customerId"));

        log.info("[save-customer-interaction] index={} customerId={} textLen={}",
                indexName, customerId, documentText.length());

        if (indexName.isBlank()) {
            log.warn("[save-customer-interaction] missing indexName, skipping");
            return;
        }
        if (documentText.isBlank()) {
            log.warn("[save-customer-interaction] empty documentText, skipping");
            return;
        }

        float[] vec = embeddings.embed(documentText);
        if (vec == null) {
            log.error("[save-customer-interaction] embedding failed, skipping store");
            return;
        }

        String id = store.index(indexName, documentText, vec, customerId);
        log.info("[save-customer-interaction] stored id={}", id);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
