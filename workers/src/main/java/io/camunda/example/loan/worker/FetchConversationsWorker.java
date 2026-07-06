package io.camunda.example.loan.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.example.loan.rag.EsVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Job type: fetch-past-conversations
 *
 * BPMN contract (from ioMapping):
 *   input  customerId = currentEmail.fromAddress
 *   header resultVariable = customerPreviousConversationContext
 *   boundary event "No past conversations found"
 *
 * Returns the customer's recent interaction history as a text block that the
 * AI Agent injects into its system prompt ("customerPreviousConversationContext").
 *
 * NOTE: this is a customerId filter + recency sort, NOT a semantic search —
 * the BPMN passes no query here, only the customer identity.
 */
@Component
public class FetchConversationsWorker {

    private static final Logger log = LoggerFactory.getLogger(FetchConversationsWorker.class);

    private static final int MAX_HISTORY = 5;

    private final EsVectorStore store;

    public FetchConversationsWorker(EsVectorStore store) {
        this.store = store;
    }

    @JobWorker(type = "fetch-past-conversations")
    public Map<String, Object> fetchPastConversations(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        String customerId = str(vars.get("customerId"));

        // The per-customer index name mirrors the save side.
        String indexName = "conversation-knowledge-base-" + customerId;

        log.info("[fetch-past-conversations] customerId={} index={}", customerId, indexName);

        List<EsVectorStore.Chunk> history = customerId.isBlank()
                ? List.of()
                : store.recentByCustomer(indexName, customerId, MAX_HISTORY);

        boolean hasHistory = !history.isEmpty();

        String context = hasHistory
                ? history.stream().map(EsVectorStore.Chunk::text).collect(Collectors.joining("\n---\n"))
                : "";

        log.info("[fetch-past-conversations] records={} hasHistory={}", history.size(), hasHistory);

        Map<String, Object> result = new HashMap<>();
        // The resultVariable header maps this worker's return into customerPreviousConversationContext,
        // but we also set it explicitly so behavior is unambiguous regardless of header handling.
        result.put("customerPreviousConversationContext", context);
        result.put("hasPastConversations", hasHistory);
        return result;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
