# Architecture

## System overview

```
 Inbound email
      |
      v
+--------------------------------------------------------------------+
|  Camunda 8.10 Orchestration (Zeebe broker + Operate/Tasklist)      |
|                                                                    |
|  Process: AI Email Support Agent                                   |
|   0. Domain scope decision (DMN)  --OUT_OF_SCOPE--> Escalated [END] |
|          | IN_SCOPE                                                 |
|   1. Fetch past conversations   -> workers (ES query)              |
|   2. Query knowledge base (RAG) -> workers + embeddings svc        |
|   3. Agent decision (LLM)       -> llm-stub  (or AWS/Titan)        |
|   4a. Resolve autonomously  OR  4b. Escalate to human task         |
|   5. Save interaction to long-term memory -> workers (ES)          |
|   6. Agent as a Judge (LLM)     -> evaluates the interaction       |
+--------------------------------------------------------------------+
      |                 |                  |
      v                 v                  v
 Elasticsearch     Optimize          Connectors
```

## Guardrails

Two independent, deterministic guardrails run BEFORE the agent, at different
concerns:

**Out-of-scope rejection (behavioural governance).** A DMN decision table
(`process/dmn/domain-scope-guardrail.dmn`) classifies the inbound message by
keyword: loan-domain terms => IN_SCOPE, clear off-domain terms with no loan term
=> OUT_OF_SCOPE, ambiguous => IN_SCOPE (deferred to the agent). A business rule
task evaluates it first; an exclusive gateway routes OUT_OF_SCOPE to an
escalation end event, so off-domain requests never reach the LLM. Deterministic
and provable, unlike a prompt-based rule (which depends on the model honouring
the prompt — and failed silently against the test stub).

**PII redaction (data protection).** The `PiiRedactor` (workers) redacts emails,
phones, SSNs, and account IDs from the message before any LLM call, so sensitive
data never leaves the boundary. Deterministic and unit-tested.

## Components and responsibilities

**Orchestration (`camunda/camunda:8.10`)** — runs the process, exposes
Operate/Tasklist, evaluates DMN decisions, and exports data two ways:
`CamundaExporter` feeds Operate/Tasklist; the legacy `ElasticsearchExporter`
produces `zeebe-record-*` indices that Optimize imports.

**Workers (Spring Boot, Java 21)** — job workers for fetch-past-conversations,
query-knowledge-base, save-customer-interaction, save-to-knowledge-base. Hold
the RAG logic (embeddings lookup against the `specialist-knowledge-base`
dense-vector index) and the `PiiRedactor` guardrail.

**LLM stub (`services/llm-stub`)** — required runtime service standing in for a
paid LLM so the flow completes without cost. Production swaps this for a real
tiered LLM (AWS Bedrock, `eu-north-1`).

**Embeddings (`services/embeddings`)** — `all-mpnet-base-v2` sentence-transformers
service producing vectors for the RAG index.

**Metrics (`services/metrics`, legacy)** — `collect_metrics.py` writes to the
`llm-metrics` ES index for Kibana; largely superseded by Optimize.

## Data stores

- **Elasticsearch 8.19.4** — Camunda secondary storage, RAG `dense_vector`
  index (`specialist-knowledge-base`), and `zeebe-record-*` export indices.
- **PostgreSQL** — Keycloak and Web Modeler backing store.

## Secret flow

Connector secrets load via Docker secrets (files in `/run/secrets/`, tmpfs), not
compose env. Worker secrets load from a git-ignored `.env`. No secret is
committed. See `docs/Secrets-Threat-Model.docx`.

## Deployment boundary

This repo versions our code, config, and process definitions (BPMN + DMN), not
the Camunda distribution. Camunda runs as official images pulled by compose.
BPMN and DMN deploy together — the process's business rule task calls the
decision by id (`domain_scope_decision`).
