# Architecture

## System overview

```
 Inbound email
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│  Camunda 8.10 Orchestration (Zeebe broker + Operate/Tasklist)│
│                                                              │
│  Process_0j5qzil — AI Email Support Agent                    │
│   1. Fetch past conversations   ─► workers (ES query)        │
│   2. Query knowledge base (RAG) ─► workers + embeddings svc  │
│   3. Agent decision (LLM)       ─► llm-stub  (or AWS/Titan)  │
│   4a. Resolve autonomously  OR  4b. Escalate to human task   │
│   5. Save interaction to long-term memory ─► workers (ES)    │
└─────────────────────────────────────────────────────────────┘
      │                 │                  │
      ▼                 ▼                  ▼
 Elasticsearch     Optimize          Connectors
 (dense_vector,    (zeebe-record-*   (Slack, SendGrid,
  specialist-kb,    heat maps)        email, LLM)
  zeebe-record-*)
```

## Components and responsibilities

**Orchestration (Camunda `camunda/camunda:8.10`)** — runs the process,
exposes Operate/Tasklist, and exports data two ways: `CamundaExporter` feeds
Operate/Tasklist; the legacy `ElasticsearchExporter` produces `zeebe-record-*`
indices that Optimize imports. Config: `deploy/config/orchestration/application.yaml`.

**Workers (Spring Boot, Java 21)** — job workers for `fetch-past-conversations`,
`query-knowledge-base`, `save-customer-interaction`, `save-to-knowledge-base`.
Hold the RAG logic: embeddings lookup against the `specialist-knowledge-base`
dense-vector index using the official ES Java client.

**LLM stub (`services/llm-stub`)** — required runtime service. Stands in for a
paid LLM so the flow completes without incurring cost. The production path swaps
this for a real tiered LLM (AWS Bedrock, `eu-north-1`) — the only cost-incurring
enhancement on the roadmap.

**Embeddings (`services/embeddings`)** — `all-mpnet-base-v2` sentence-transformers
service producing vectors for the RAG index.

**Metrics (`services/metrics`, legacy)** — `collect_metrics.py` writes to the
`llm-metrics` ES index for Kibana Lens charts. Largely superseded by Optimize;
retained for LLM-level latency/cost metrics Optimize doesn't capture.

## Data stores

- **Elasticsearch 8.19.4** — three roles: Camunda secondary storage, RAG
  `dense_vector` index (`specialist-knowledge-base`), and `zeebe-record-*`
  export indices for Optimize.
- **PostgreSQL** — Keycloak and Web Modeler backing store.

## Secret flow

Secrets load into the connectors service via `env_file` only
(`deploy/secrets/connector-secrets.txt`, git-ignored). No secret is hardcoded in
compose. See `docs/Secrets-Threat-Model.docx`.

## Deployment boundary

This repo versions **our code and config**, not the Camunda distribution. Camunda
runs as official images pulled by compose. The distribution folder is a local
dependency, never committed.
