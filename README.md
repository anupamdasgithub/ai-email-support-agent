# AI Email Support Agent

Agentic email-support orchestration on **Camunda 8 Self-Managed**. An inbound
customer email triggers a process that first checks domain scope, then fetches
prior conversation context, queries a RAG knowledge base, lets an agent decide,
and either resolves the case autonomously or escalates to a human — with the
resolution saved back to long-term memory. An "Agent as a Judge" step evaluates
each interaction.

Blueprint: <https://marketplace.camunda.com/en-US/apps/522492/ai-email-support-agent>
---

## Camunda version support

The upstream blueprint targets **Camunda 8.8**. This repository was originally
built and committed against **8.10-SNAPSHOT**, then validated on 8.8.

| Version | Status |
|---------|--------|
| **8.8.x** (8.8.33 / connectors 8.8.15) | Full flow runs end to end. Recommended. |
| **8.10-SNAPSHOT** | Everything runs up to the agent's history write, which fails — see below. |

**Known limitation on 8.10-SNAPSHOT.** The AI Agent connector fails when writing
its history item:

```
POST /v2/agent-instances/{key}/history
400: Request property [loopIteration] cannot be parsed
```

`AgentInstanceHistoryItem` renamed `iteration` to `loopIteration`, and the
`connectors-bundle` and `camunda` SNAPSHOT images are published on separate
build pipelines. When the two images straddle that rename they disagree on the
field name, and Docker Hub does not always offer an aligned pair. Both images
freshly pulled still reproduced it. Not fixable through configuration — run on
8.8, or wait for aligned SNAPSHOT builds.

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
- Camunda 8.8 self-managed images (pulled by compose)
- JDK 21 + Maven (to build `workers/`)


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
`process/dmn/domain-scope-guardrail.dmn` together — the process calls the
decision by id, so a BPMN-only deployment fails at runtime.

### Ports differ between 8.8 and 8.10

On 8.8, Operate and Tasklist are paths on the consolidated orchestration app,
published on **8088** rather than 8080:

| | 8.8 | 8.10 |
|---|---|---|
| Operate | `localhost:8088/operate` | `localhost:8080/operate` |
| Tasklist | `localhost:8088/tasklist` | `localhost:8080/tasklist` |
| Web Modeler | `localhost:8070` | `localhost:8070` |
| Optimize | `localhost:8083` | `localhost:8083` |
| Identity | `localhost:8084` | `localhost:8084` |

The workers' `application.yml` must point `rest-address` at the right one.

### Gotcha: Optimize login loop on 8.8

`.env` is loaded into every container, so `CAMUNDA_IDENTITY_CLIENT_SECRET`
(Identity's secret) binds to `camunda.identity.clientSecret` inside Optimize and
overrides the value from `application-ccsm.yaml`. Optimize then presents
`clientId=optimize` with Identity's secret, Keycloak answers
`CODE_TO_TOKEN_ERROR / invalid_client_credentials`, no session cookie is set, and
the browser loops between Optimize and Keycloak with no error logged by Optimize.

Fix — pin it back in the optimize service:

```yaml
      CAMUNDA_IDENTITY_CLIENT_SECRET: ${OPTIMIZE_CLIENT_SECRET}
```

Verify with `wget -qO- http://localhost:8092/actuator/configprops` inside the
container; `clientSecret` should read the Optimize secret, and its `origin`
field names whichever source actually won.

## Guardrails (governance)

- **Out-of-scope rejection (deterministic)** — `process/dmn/domain-scope-guardrail.dmn`
  classifies each inbound email by keyword before any LLM call. A DMN business
  rule task runs first; a gateway routes `OUT_OF_SCOPE` messages to an escalation
  end event, so off-domain requests never reach the agent. Deterministic and
  provable, independent of the LLM. See `docs/OutOfScope-DMN-Test-Note.md`.


## Security

Secrets are **never** committed. `.env`, `connector-secrets.txt`, and the Docker
secrets directory are git-ignored; only `*.template` files with placeholders are
tracked. Any credential ever exposed must be **rotated**, not just removed.

## Lifecycle

See `LIFECYCLE.md` - prompt/model registry, CI check (`scripts/ci-check.sh` plus
`.github/workflows/ci.yml`), and rollback via the previous tagged BPMN
(`git tag` scheme `bpmn-v<N>-good`; latest verified: `bpmn-v9-good`).

## Observability

- **Operate / Tasklist** - process/task state; DMN decision evaluations
- **Optimize** - heat maps and analysis (`zeebe-record-*` via ElasticsearchExporter)
- **Kibana** - legacy `llm-metrics` dashboards (optional)

## Attribution & License

Built on **Camunda 8** and the **Camunda AI Agent blueprint**. The orchestration
patterns and the BPMN foundation are Camunda's; the guardrails (PII redaction,
DMN domain-scope classifier), workers, supporting services, deployment
configuration, and documentation are original additions authored for learning
and demonstration.

The original contributions in this repository are released under the **MIT
License** (see `LICENSE`) — in the same open spirit that Camunda shares its
blueprints. Camunda's platform, connectors, and original blueprint BPMN remain
Camunda's and are governed by Camunda's own license terms.
