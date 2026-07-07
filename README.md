# AI Email Support Agent

Agentic email-support orchestration on **Camunda 8.10 Self-Managed**. An inbound
customer email triggers a process that first checks domain scope, then fetches
prior conversation context, queries a RAG knowledge base, lets an agent decide,
and either resolves the case autonomously or escalates to a human — with the
resolution saved back to long-term memory. An "Agent as a Judge" step evaluates
each interaction.

## Components

| Path | What it is |
|------|-----------|
| `process/bpmn/` | The BPMN process definition and its embedded agent/judge prompts |
| `process/dmn/` | DMN decision tables (domain-scope guardrail) |
| `workers/` | Spring Boot job workers (Java 21): fetch-past-conversations, query-knowledge-base, save-customer-interaction, save-to-knowledge-base; RAG + Elasticsearch dense-vector logic; PII redaction guardrail |
| `services/llm-stub/` | Required local LLM stub the flow calls in place of a paid LLM |
| `services/embeddings/` | `all-mpnet-base-v2` sentence-transformers embedding service |
| `services/metrics/` | `collect_metrics.py` -> `llm-metrics` ES index (legacy; superseded by Optimize) |
| `deploy/config/` | Camunda service configuration (orchestration, connectors, identity, optimize) |
| `scripts/` | `ci-check.sh` and operational helpers |
| `prompt-model-registry.yaml` | Versioned record of prompts, models, dates, rationale |
| `LIFECYCLE.md` | Versioning, CI, and rollback procedure |

> The Docker Compose file and all secrets are intentionally **not** committed.
> The repo versions code, config, and process definitions; the running stack is
> assembled locally from the official Camunda images.

## Prerequisites

- Docker + Docker Compose
- Camunda 8.10 self-managed images (pulled by compose)
- JDK 21 + Maven (to build `workers/`)
- Apple Silicon supported

## Run (local)

Secrets live only on disk, never in git. Create them from the templates:

```bash
cp workers/.env.template workers/.env      # fill in real values
```

Bring up the stack (compose lives in your local Camunda distribution, not this
repo), then build and run the workers:

```bash
cd workers
./build.sh && ./run.sh
```

Deploy `process/bpmn/ai-email-support-agent.bpmn` and
`process/dmn/domain-scope-guardrail.dmn` to the running cluster via Web Modeler
(both must be deployed together — the process calls the decision).

## Guardrails (governance)

- **Out-of-scope rejection (deterministic)** — `process/dmn/domain-scope-guardrail.dmn`
  classifies each inbound email by keyword before any LLM call. A DMN business
  rule task runs first; a gateway routes `OUT_OF_SCOPE` messages to an escalation
  end event, so off-domain requests (e.g. weather, restaurants) never reach the
  agent. Deterministic and provable, independent of the LLM. See
  `docs/OutOfScope-DMN-Test-Note.md`.
- **PII redaction** — the `PiiRedactor` in `workers` deterministically redacts
  emails, phones, SSNs, and account IDs before any LLM call. Unit-tested
  (`PiiRedactorTest`). See `docs/PII-Guardrail-Test-Note.md`.

## Security

Secrets are **never** committed. `.env`, `connector-secrets.txt`, and the Docker
secrets directory are git-ignored; only `*.template` files with placeholders are
tracked. Any credential ever exposed must be **rotated**, not just removed.
Connector secrets use Docker secrets (files in tmpfs), not compose env.

## Lifecycle

See `LIFECYCLE.md` - prompt/model registry, CI check (`scripts/ci-check.sh` plus
`.github/workflows/ci.yml`), and rollback via the previous tagged BPMN
(`git tag` scheme `bpmn-v<N>-good`; latest verified: `bpmn-v9-good`).

## Observability

- **Operate / Tasklist** - process/task state; DMN decision evaluations
- **Optimize** - heat maps and analysis (`zeebe-record-*` via ElasticsearchExporter)
- **Kibana** - legacy `llm-metrics` dashboards (optional)
