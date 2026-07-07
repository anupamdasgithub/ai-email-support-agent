# Lifecycle Management

How prompts, models, and process definitions are versioned, checked, and rolled
back for the AI Email Support Agent.

## What is versioned

- **BPMN** (`process/bpmn/`) — the process definition and all its prompts.
- **Workers** (`workers/`) — the Java job workers.
- **Compose + config** (`deploy/`) — how the stack runs (secrets excluded via
  `.gitignore`).
- **Prompt & model choices** (`prompt-model-registry.yaml`) — the prompt text,
  model, date, and rationale for each agent step.

All of the above live in git. Secrets never do (see `.gitignore`).

## Prompt / model registry

`prompt-model-registry.yaml` records, per agent step: the model, endpoint, a
prompt summary, where the full prompt lives, and the rationale. When a prompt or
model changes, update the entry (bump its `version`), add a `changelog` line, and
commit. Git history then answers "what did the agent use on date X, and why?"

## CI check (gate before release)

`scripts/ci-check.sh` (and `.github/workflows/ci.yml` for hosted CI) runs two
gates:
1. **Build workers** — `mvn compile` must succeed.
2. **Validate BPMN** — every `.bpmn` must be well-formed XML with a `<process>`.

Run locally before committing:
```bash
bash scripts/ci-check.sh
```
A non-zero exit blocks the release. On GitHub, the same runs automatically on
push / PR to `main`.

## Tagging a known-good release

When a BPMN version is deployed and confirmed working, tag it so it can be
redeployed later:

```bash
git tag -a bpmn-v5-good -m "Known-good: version 5, all instances completing"
git push origin bpmn-v5-good        # only if using a remote
```

Use a clear tag scheme, e.g. `bpmn-v<N>-good`. The tag captures the exact BPMN
(and registry, workers, compose) at that commit.

## Rollback procedure (documented)

If a newly deployed BPMN misbehaves, roll back by redeploying the previous
known-good tagged version:

1. **Identify the last good tag:**
   ```bash
   git tag --list 'bpmn-*-good'
   ```
2. **Check out that BPMN** (into a detached state or a branch):
   ```bash
   git checkout bpmn-v5-good -- process/bpmn/ai-email-support-agent.bpmn
   ```
   This restores the tagged BPMN file into your working tree without moving the
   whole repo.
3. **Redeploy that BPMN** to the cluster — via Web Modeler (open the restored
   file and Deploy) or the deploy REST endpoint. Deploying an older definition
   creates a new version in Zeebe that points back to the known-good process.
4. **Verify** in Operate that new instances use the rolled-back definition and
   complete normally.
5. **Record** the rollback: add a `changelog` line in the registry noting what
   was rolled back and why.

Rollback is "redeploy the previous tagged BPMN" — the tag guarantees you are
redeploying the exact bytes that were known to work, not a reconstruction.

## Everyday flow

1. Change a prompt / model / worker / BPMN.
2. Update `prompt-model-registry.yaml` if a prompt or model changed.
3. `bash scripts/ci-check.sh` — must pass.
4. Commit (and push if using a remote).
5. Deploy; if confirmed good, tag `bpmn-v<N>-good`.
6. If it breaks, roll back to the previous `-good` tag (above).
