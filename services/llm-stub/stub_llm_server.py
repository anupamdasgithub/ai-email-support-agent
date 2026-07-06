#!/usr/bin/env python3
"""
Minimal OpenAI-compatible stub LLM server for testing Camunda's
AI Agent connector end-to-end without burning real Anthropic credits.

Behavior:
- Reads the incoming OpenAI-shaped chat-completions request.
- Counts how many prior assistant turns exist in the conversation
  (by counting messages with role == "assistant").
- On the FIRST agent turn: picks the tool whose name contains
  "knowledge" (case-insensitive) from the incoming `tools` array
  and returns a tool_call requesting it, with a guessed query arg.
- On the SECOND agent turn (i.e. after a tool result has come back):
  returns a plain text final answer, no tool call, ending the loop.

This does NOT hardcode exact tool names — it inspects whatever
Camunda actually sent, so it stays correct even if display names
differ slightly from what we expect.

Run: python3 stub_llm_server.py
Listens on http://0.0.0.0:9999
"""

import json
import logging
import time
import uuid

from flask import Flask, jsonify, request

app = Flask(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("stub-llm")


def count_assistant_turns(messages):
    return sum(1 for m in messages if m.get("role") == "assistant")


def find_tool_by_keyword(tools, keyword):
    """Find a tool from the incoming request whose name or description
    contains the given keyword (case-insensitive). Returns the raw
    tool dict (OpenAI function-calling shape) or None."""
    keyword = keyword.lower()
    for t in tools or []:
        fn = t.get("function", t)  # tolerate both {function:{...}} and flat shape
        name = (fn.get("name") or "").lower()
        desc = (fn.get("description") or "").lower()
        if keyword in name or keyword in desc:
            return t
    return None


def first_required_param(tool):
    """Pull the first property name from a tool's JSON schema, to know
    which argument key the stub should populate."""
    fn = tool.get("function", tool)
    schema = fn.get("parameters", {}) or {}
    props = schema.get("properties", {}) or {}
    if props:
        return next(iter(props.keys()))
    return "query"


@app.route("/v1/chat/completions", methods=["POST"])
def chat_completions():
    body = request.get_json(force=True, silent=True) or {}
    messages = body.get("messages", [])
    tools = body.get("tools", [])
    model = body.get("model", "stub-model")

    assistant_turns = count_assistant_turns(messages)

    log.info("Incoming request: model=%s, messages=%d, tools=%d, assistant_turns=%d",
              model, len(messages), len(tools), assistant_turns)
    for t in tools:
        fn = t.get("function", t)
        log.info("  tool offered: %s", fn.get("name"))

    completion_id = f"chatcmpl-stub-{uuid.uuid4().hex[:12]}"
    created = int(time.time())

    if assistant_turns == 0:
        # First turn: call the knowledge-base tool if offered, else just answer.
        kb_tool = find_tool_by_keyword(tools, "knowledge")
        if kb_tool:
            fn = kb_tool.get("function", kb_tool)
            tool_name = fn.get("name")
            param_key = first_required_param(kb_tool)

            # Try to extract the customer's question from the last user message
            user_text = ""
            for m in reversed(messages):
                if m.get("role") == "user":
                    content = m.get("content", "")
                    user_text = content if isinstance(content, str) else json.dumps(content)
                    break

            tool_call_id = f"call_stub_{uuid.uuid4().hex[:12]}"
            args = {param_key: f"home loan eligibility and rates related to: {user_text[:200]}"}

            log.info("STUB DECISION: calling tool '%s' with args=%s", tool_name, args)

            response = {
                "id": completion_id,
                "object": "chat.completion",
                "created": created,
                "model": model,
                "choices": [
                    {
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": tool_call_id,
                                    "type": "function",
                                    "function": {
                                        "name": tool_name,
                                        "arguments": json.dumps(args),
                                    },
                                }
                            ],
                        },
                        "finish_reason": "tool_calls",
                    }
                ],
                "usage": {
                    "prompt_tokens": 500,
                    "completion_tokens": 40,
                    "total_tokens": 540,
                },
            }
            return jsonify(response)

    # Second turn (or no kb tool found): give a final plain-text answer.
    log.info("STUB DECISION: returning final answer, no further tool calls")

    final_text = (
        "Thank you for reaching out about your home loan inquiry. Based on the "
        "information available, for a loan amount of 50 lakhs with an annual "
        "income of 12 lakhs (salaried), you would likely qualify for our "
        "standard home loan product at competitive rates, subject to final "
        "underwriting review. A loan specialist will follow up with exact "
        "terms.\n\nDoes this fully resolve your loan inquiry?"
    )

    response = {
        "id": completion_id,
        "object": "chat.completion",
        "created": created,
        "model": model,
        "choices": [
            {
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": final_text,
                },
                "finish_reason": "stop",
            }
        ],
        "usage": {
            "prompt_tokens": 600,
            "completion_tokens": 80,
            "total_tokens": 680,
        },
    }
    return jsonify(response)


@app.route("/v1/models", methods=["GET"])
def list_models():
    # Some OpenAI-compatible clients probe this endpoint on startup.
    return jsonify({
        "object": "list",
        "data": [{"id": "claude-sonnet-4-6", "object": "model", "owned_by": "stub"}],
    })


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok"})


if __name__ == "__main__":
    log.info("Starting stub LLM server on http://0.0.0.0:9999")
    app.run(host="0.0.0.0", port=9999, debug=False)
