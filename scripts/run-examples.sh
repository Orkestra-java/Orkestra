#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
jdk_bin=${JAVA_HOME:+$JAVA_HOME/bin/}
module_path=$(python3 scripts/project.py)
"${jdk_bin}java" --module-path "$module_path" --add-modules io.orkestra.core examples/PlaceOrder.java
"${jdk_bin}java" --module-path "$module_path" --add-modules io.orkestra.core examples/WorkflowExample.java
"${jdk_bin}java" --module-path "$module_path" --add-modules io.orkestra.core examples/parallel/ParallelExample.java
