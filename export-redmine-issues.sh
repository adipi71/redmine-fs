#!/usr/bin/env bash
# Compila (se necessario) ed esegue RedmineIssuesExporter.java: si connette
# al DB PostgreSQL configurato in etc/db.properties, esegue le query in
# sql/redmine_issues.sql e sql/redmine_projects.sql e scrive un TSV per
# ciascuna in output/ (stesso nome base del file .sql).
#
# Uso:
#   ./export-redmine-issues.sh                                       # usa i path di default
#   ./export-redmine-issues.sh <query.sql> [<query2.sql> ...]         # solo le sql passate
#   ./export-redmine-issues.sh --db <db.properties> --out <outputDir> [<query.sql> ...]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_DIR="$SCRIPT_DIR/java"
LIB_DIR="$SCRIPT_DIR/lib"
SRC="$JAVA_DIR/RedmineIssuesExporter.java"
CLASS="$JAVA_DIR/RedmineIssuesExporter.class"
DRIVER_JAR="$(find "$LIB_DIR" -maxdepth 1 -name 'postgresql-*.jar' | sort -V | tail -1)"

if [[ -z "$DRIVER_JAR" ]]; then
    echo "Driver JDBC PostgreSQL non trovato in $LIB_DIR (atteso postgresql-*.jar)" >&2
    exit 1
fi

if [[ ! -f "$CLASS" || "$SRC" -nt "$CLASS" ]]; then
    echo "Compilazione RedmineIssuesExporter.java..." >&2
    javac -cp "$DRIVER_JAR" -d "$JAVA_DIR" "$SRC"
fi

java -cp "$JAVA_DIR:$DRIVER_JAR" RedmineIssuesExporter "$@"
