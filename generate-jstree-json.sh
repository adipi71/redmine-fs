#!/usr/bin/env bash
# Compila (se necessario) ed esegue JstreeJsonBuilder.java per rigenerare
# jstree-projects-issues.json a partire dai TSV in sql/.
#
# Uso:
#   ./generate-jstree-json.sh                                   # usa i path di default
#   ./generate-jstree-json.sh <projects.tsv> <issues.tsv> <out.json>
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_DIR="$SCRIPT_DIR/java"
SRC="$JAVA_DIR/JstreeJsonBuilder.java"
CLASS="$JAVA_DIR/JstreeJsonBuilder.class"

if [[ ! -f "$CLASS" || "$SRC" -nt "$CLASS" ]]; then
    echo "Compilazione JstreeJsonBuilder.java..." >&2
    javac -d "$JAVA_DIR" "$SRC"
fi

java -cp "$JAVA_DIR" JstreeJsonBuilder "$@"
