#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar" ]]; then
  exec java -classpath "$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
echo "Gradle not found. Use GitHub Actions (setup-gradle@v4) or generate the wrapper using 'gradle wrapper'." >&2
exit 127
