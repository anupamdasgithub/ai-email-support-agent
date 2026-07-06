#!/usr/bin/env bash
# ============================================================
# build.sh — always runs Maven under Java 21 (Homebrew OpenJDK)
# regardless of which Java is active in your shell.
#
# Your Mac has three JVMs:
#   Java 25  (Oracle)  — default, BREAKS Lombok
#   Java 24  (Oracle)  — also breaks Lombok
#   Java 21  (Homebrew OpenJDK) — ✓ correct one
#
# Run: chmod +x build.sh && ./build.sh
# ============================================================

set -e

JAVA_21=$(/usr/libexec/java_home -v 21 2>/dev/null)

if [ -z "$JAVA_21" ]; then
  echo "ERROR: Java 21 not found. Install with: brew install openjdk@21"
  exit 1
fi

echo "Using JAVA_HOME: $JAVA_21"
export JAVA_HOME="$JAVA_21"
export PATH="$JAVA_HOME/bin:$PATH"

java -version

# Clean build, skip tests for speed
mvn clean package -DskipTests "$@"

echo ""
echo "Build successful. Run with:"
echo "  JAVA_HOME=$JAVA_21 java -jar target/bank-ai-loan-approval-1.0.0-SNAPSHOT.jar"
