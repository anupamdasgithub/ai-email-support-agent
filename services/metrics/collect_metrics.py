#!/usr/bin/env python3
"""
LLM Metrics Collector (Camunda 8.10 self-managed, Docker Compose)

Harvests per-instance LLMOps metrics from the AI Email Support Agent process
and writes them to the 'llm-metrics' Elasticsearch index for Kibana dashboards.

Captured per instance:
  - inputTokenCount, outputTokenCount, modelCalls  (from metrics_* variables)
  - totalTokens                                    (computed)
  - latencyMs                                      (endDate - startDate)
  - decision                                       (Agent-as-Judge verdict, if present)
  - processDefinitionId, version, state, startDate

Idempotent: uses processInstanceKey as the ES document _id, so re-running
updates rather than duplicates. Backfills all existing instances, and picks up
new ones on each run.

Usage:  python3 collect_metrics.py
Env overrides: KEYCLOAK_URL, ZEEBE_API, ES_URL, PROCESS_ID, CLIENT_ID, CLIENT_SECRET
"""
import os
import sys
import json
import datetime
import urllib.request
import urllib.parse

KEYCLOAK_URL = os.environ.get("KEYCLOAK_URL", "http://localhost:18080")
ZEEBE_API    = os.environ.get("ZEEBE_API",    "http://localhost:8080")
ES_URL       = os.environ.get("ES_URL",       "http://localhost:9200")
PROCESS_ID   = os.environ.get("PROCESS_ID",   "Process_0j5qzil")
CLIENT_ID    = os.environ.get("CLIENT_ID",    "orchestration")
CLIENT_SECRET= os.environ.get("CLIENT_SECRET","secret")
INDEX        = "llm-metrics"

METRIC_VARS = {"metrics_inputTokenCount", "metrics_outputTokenCount", "metrics_modelCalls"}
EXTRA_VARS  = {"decision", "knowledgeBaseDecision", "customerId"}


def _post(url, data, token=None, form=False):
    if form:
        body = urllib.parse.urlencode(data).encode()
        headers = {"Content-Type": "application/x-www-form-urlencoded"}
    else:
        body = json.dumps(data).encode()
        headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode() or "{}")


def _put(url, data):
    body = json.dumps(data).encode()
    req = urllib.request.Request(url, data=body,
                                 headers={"Content-Type": "application/json"}, method="PUT")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def get_token():
    resp = _post(f"{KEYCLOAK_URL}/auth/realms/camunda-platform/protocol/openid-connect/token",
                 {"client_id": CLIENT_ID, "client_secret": CLIENT_SECRET,
                  "grant_type": "client_credentials"}, form=True)
    return resp["access_token"]


def ensure_index():
    mapping = {
        "mappings": {
            "properties": {
                "processInstanceKey":  {"type": "keyword"},
                "processDefinitionId": {"type": "keyword"},
                "version":             {"type": "integer"},
                "state":               {"type": "keyword"},
                "inputTokenCount":     {"type": "long"},
                "outputTokenCount":    {"type": "long"},
                "totalTokens":         {"type": "long"},
                "modelCalls":          {"type": "integer"},
                "latencyMs":           {"type": "long"},
                "decision":            {"type": "keyword"},
                "knowledgeBaseDecision":{"type": "keyword"},
                "customerId":          {"type": "keyword"},
                "startDate":           {"type": "date"},
                "endDate":             {"type": "date"},
                "collectedAt":         {"type": "date"},
            }
        }
    }
    status, _ = _put(f"{ES_URL}/{INDEX}", mapping)
    if status == 200:
        print(f"[index] created {INDEX}")
    else:
        print(f"[index] {INDEX} already exists (ok)")


def list_instances(token):
    resp = _post(f"{ZEEBE_API}/v2/process-instances/search", token=token,
                 data={"filter": {"processDefinitionId": PROCESS_ID},
                       "page": {"limit": 1000}})
    return resp.get("items", [])


def get_instance_vars(token, instance_key):
    resp = _post(f"{ZEEBE_API}/v2/variables/search", token=token,
                 data={"filter": {"processInstanceKey": str(instance_key)},
                       "page": {"limit": 1000}})
    wanted = METRIC_VARS | EXTRA_VARS
    out = {}
    for v in resp.get("items", []):
        if v["name"] in wanted:
            out[v["name"]] = v["value"]
    return out


def _num(val, default=0):
    try:
        return int(str(val).strip().strip('"'))
    except (ValueError, TypeError):
        return default


def _clean_str(val):
    if val is None:
        return None
    return str(val).strip().strip('"')


def _parse_dt(s):
    if not s:
        return None
    try:
        return datetime.datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None


def build_doc(inst, vars_):
    key = str(inst.get("processInstanceKey"))
    start = inst.get("startDate")
    end = inst.get("endDate")
    sd, ed = _parse_dt(start), _parse_dt(end)
    latency = int((ed - sd).total_seconds() * 1000) if (sd and ed) else None

    intok = _num(vars_.get("metrics_inputTokenCount"))
    outok = _num(vars_.get("metrics_outputTokenCount"))
    calls = _num(vars_.get("metrics_modelCalls"))

    return {
        "processInstanceKey": key,
        "processDefinitionId": inst.get("processDefinitionId"),
        "version": inst.get("processDefinitionVersion"),
        "state": inst.get("state"),
        "inputTokenCount": intok,
        "outputTokenCount": outok,
        "totalTokens": intok + outok,
        "modelCalls": calls,
        "latencyMs": latency,
        "decision": _clean_str(vars_.get("decision")),
        "knowledgeBaseDecision": _clean_str(vars_.get("knowledgeBaseDecision")),
        "customerId": _clean_str(vars_.get("customerId")),
        "startDate": start,
        "endDate": end,
        "collectedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    }


def index_doc(doc):
    # Use instance key as _id -> idempotent upsert
    url = f"{ES_URL}/{INDEX}/_doc/{doc['processInstanceKey']}"
    status, _ = _put(url, doc)
    return status


def main():
    print("[metrics] authenticating...")
    token = get_token()
    ensure_index()

    instances = list_instances(token)
    print(f"[metrics] found {len(instances)} instances of {PROCESS_ID}")

    harvested = 0
    for inst in instances:
        key = inst.get("processInstanceKey")
        vars_ = get_instance_vars(token, key)
        if not (METRIC_VARS & set(vars_.keys())):
            # No metrics on this instance (e.g. ran before mappings existed)
            continue
        doc = build_doc(inst, vars_)
        status = index_doc(doc)
        ok = status in (200, 201)
        harvested += 1 if ok else 0
        print(f"  {key}  tokens={doc['totalTokens']} calls={doc['modelCalls']} "
              f"latency={doc['latencyMs']}ms decision={doc['decision']}  -> {'ok' if ok else 'FAIL '+str(status)}")

    # refresh so Kibana sees data immediately
    _post(f"{ES_URL}/{INDEX}/_refresh", data={})
    print(f"[metrics] harvested {harvested} instances into '{INDEX}'")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"[metrics] ERROR: {e}", file=sys.stderr)
        sys.exit(1)
