#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
java_version="$("$java_bin" -version 2>&1 | head -n 1)"
if [[ ! "$java_version" =~ \"(2[1-9]|[3-9][0-9])\. ]]; then
  echo "Configure JAVA_HOME para um JDK 21 ou superior. Atual: $java_version" >&2
  exit 1
fi
node -e 'const [major, minor] = process.versions.node.split(".").map(Number); if (major < 22 || (major === 22 && minor < 13)) throw new Error("Use Node 22.13+ ou 24 LTS")'
./gradlew test checkstyleMain checkstyleTest assemble --console=plain
(
  cd traccar-web
  npm ci --no-audit --no-fund
  (cd demo && npm ci --no-audit --no-fund)
  node --test src/common/util/liveUpdates.test.js
  npm run lint
  npm run build
)
