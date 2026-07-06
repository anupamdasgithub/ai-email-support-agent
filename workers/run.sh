#!/usr/bin/env bash
# Workers run as a local JVM process (not inside the Docker network), so the
# embeddings service and Elasticsearch are reached via published host ports on
# localhost — NOT via their in-network container hostnames.
#
# Build targets Java 21 (see pom.xml). Use a Java 21 JDK to run this jar.
set -e

[ -f ".env" ] && export $(grep -v '^#' .env | grep -v '^$' | xargs)

# --- RAG local-run wiring -------------------------------------------------
# Override only if not already set in .env, so .env stays authoritative.
: "${EMBEDDINGS_SERVICE_URL:=http://localhost:8000}"
: "${ELASTICSEARCH_HOST:=localhost}"
: "${ELASTICSEARCH_PORT:=9200}"
: "${ELASTICSEARCH_SCHEME:=http}"
export EMBEDDINGS_SERVICE_URL ELASTICSEARCH_HOST ELASTICSEARCH_PORT ELASTICSEARCH_SCHEME
# --------------------------------------------------------------------------

java -jar target/bank-ai-loan-approval-1.0.0-SNAPSHOT.jar
