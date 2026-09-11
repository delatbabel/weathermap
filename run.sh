#!/usr/bin/env bash
#
# Launch the weather map application.
#
#   ./run.sh                    the desktop application
#   ./run.sh --cli [options]    the command-line tool
#   ./run.sh --rebuild          force a rebuild first
#
# Builds only when the jar is missing or older than the sources, so repeated
# launches while testing are immediate.

set -euo pipefail
cd "$(dirname "$0")"

JAR="target/weathermap-0.1.0-SNAPSHOT.jar"
MVNW="./mvnw"

rebuild=0
args=()
for arg in "$@"; do
    case "$arg" in
        --rebuild) rebuild=1 ;;
        *) args+=("$arg") ;;
    esac
done

needs_build() {
    [[ $rebuild -eq 1 ]] && return 0
    [[ -f "$JAR" ]] || return 0
    # Any source, resource or the pom newer than the jar means a stale jar.
    [[ -n "$(find src pom.xml -newer "$JAR" -print -quit 2>/dev/null)" ]]
}

if needs_build; then
    echo "building…" >&2
    # -q keeps the build quiet; test output still appears on failure.
    "$MVNW" -q -B package
fi

# A stored preferences file is what the CLI repeats, so say where it is.
if [[ ${#args[@]} -gt 0 && "${args[0]}" == "--cli" ]]; then
    echo "preferences: ${HOME}/.weathermap/preferences.properties" >&2
fi

exec java -jar "$JAR" ${args[@]+"${args[@]}"}
