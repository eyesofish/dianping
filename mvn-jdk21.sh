#!/usr/bin/env bash
set -euo pipefail

# 若路径不同，请修改为你本机 JDK 21 的安装位置（Git Bash 路径例子：/d/tools/jdk-21.0.9+10）
JDK_HOME="${JDK_HOME:-/d/tools/jdk-21.0.9+10}"

if [ ! -d "$JDK_HOME" ]; then
  echo "JDK not found: $JDK_HOME (edit mvn-jdk21.sh to your path)" >&2
  exit 1
fi

export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

mvn "$@"
