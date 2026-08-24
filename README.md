# Redmine — Projects / Issues Hierarchy for jsTree

Generates a JSON file in [jsTree](https://www.jstree.com/) format (flat: `id`/`parent`/`text`/`icon`)
from the Redmine exports in `sql/`:

- `sql/redmine_production_projects.tsv` — projects (hierarchical via `parent_id`)
- `sql/redmine-issues.tsv` — issues (hierarchical via `parent_id`, each attached to its own project)

In the resulting JSON:
- **projects** get a folder icon (`jstree-folder`) and text `P: <project name>`
- **issues** get a file icon (`jstree-file`), and are nested under their parent issue
  (if any) or directly under their own project. Text is:
  - `A: <subject>` if the issue's tracker is **Area**
  - `#<id> <subject>` for all other trackers (Task, Request, Bug, Feature, …)

## Files

| File | Description |
|---|---|
| `java/JstreeJsonBuilder.java` | Java tool (JDK only, no external dependencies) that parses the TSVs and writes the JSON |
| `generate-jstree-json.sh` | Launcher script: compiles (if needed) and runs the Java tool |
| `main_example_launch.sh` | Quick-and-dirty example invocation, see below |
| `jstree-projects-issues.json` | Generated output (default location) — data ready for jsTree |
| `output/jstree-demo.html` | Example page that loads the JSON and renders the tree with jsTree |

## Usage

Regenerate the JSON with default paths (`sql/redmine_production_projects.tsv`,
`sql/redmine-issues.tsv` → `jstree-projects-issues.json`):

```bash
./generate-jstree-json.sh
```

With explicit paths (projects, issues, output):

```bash
./generate-jstree-json.sh /path/to/projects.tsv /path/to/issues.tsv /path/to/output.json
```

The script compiles `java/JstreeJsonBuilder.java` automatically on first run
(or if the source is newer than the `.class`); subsequent runs reuse the
already-compiled class.

**Path resolution:** arguments (when passed) are relative to the directory
you *launch the script from*, not to `java/`. Default paths, when no
arguments are passed, are always relative to the location of
`JstreeJsonBuilder.java`/`.class` (`sql/...` and `jstree-projects-issues.json`
next to `generate-jstree-json.sh`) — so they work whether you launch the
script from `redmine-fs`, or from any other directory:

```bash
# launched from redmine-fs with an explicit relative path — OK
./generate-jstree-json.sh ./sql/redmine_production_projects.tsv ./sql/redmine-issues.tsv /tmp/out.json

# launched from any other directory, no arguments — OK, uses defaults
cd /tmp && /ws/adp/redmine-fs/generate-jstree-json.sh
```

> An earlier version of the script did a `cd java/` before launching `java`,
> which broke resolution of relative paths passed as arguments (they were
> looked up inside `java/` instead of the cwd the script was launched from).
> The `cd` was removed and the default paths in the Java code are now
> computed from the `.class` location (`CodeSource`) instead of `user.dir`,
> so both cases — relative arguments and running from an arbitrary cwd —
> work correctly.

### Pruning empty projects

By default every project is kept, even ones with no content. Pass
`--prune-empty-projects` to drop projects that have neither a direct issue
nor a surviving child project (i.e. whole branches with no issues anywhere
in them) — removing an empty leaf can turn its own parent into a new empty
leaf, which is pruned too. The flag can appear anywhere among the arguments:

```bash
./generate-jstree-json.sh --prune-empty-projects
./generate-jstree-json.sh ./sql/redmine_production_projects.tsv --prune-empty-projects ./sql/redmine-issues.tsv output.json
```

With the current data this drops 29 of 96 projects (67 kept). A project
counts as having an issue based on the issue's `project_id` field — not
whether that issue ends up nested under another issue elsewhere in the
tree — so in rare cases (an issue whose `parent_id` points to a sub-task
of a *different* project) a kept project can still show up with no visible
children. That's a reflection of the underlying Redmine data, not a pruning
bug.

## Quick and dirty

To regenerate the JSON with a single command, without remembering paths/arguments:

```bash
./main_example_launch.sh
```

Equivalent to:

```bash
./generate-jstree-json.sh --prune-empty-projects ./sql/redmine_production_projects.tsv ./sql/redmine-issues.tsv output/jstree-projects-issues.json
```

Must be launched from the project root (`redmine-fs/`), since it uses
relative paths (`./sql/...`, `output/...`). Output goes to
`output/jstree-projects-issues.json` (not the root, as with
`./generate-jstree-json.sh` with no arguments), and empty projects are
pruned (see [Pruning empty projects](#pruning-empty-projects) above).
`output/` already contains a copy of `jstree-demo.html` for viewing the
generated tree — the same local server note below applies.

### Viewing the result

`output/jstree-demo.html` loads `jstree-projects-issues.json` via AJAX from
the same directory it's in — so it expects the JSON to be inside `output/`
too (as `main_example_launch.sh` produces). Open the page from a local
server (not by double-clicking / `file://`, or the browser will block the
fetch due to CORS):

```bash
python3 -m http.server 8000
# then open http://localhost:8000/output/jstree-demo.html
```

## Exporting fresh data from PostgreSQL

`export-redmine-issues.sh` connects to the Redmine PostgreSQL database, runs
the queries in `sql/redmine_issues.sql` and `sql/redmine_projects.sql` (one
connection, reused for both) and writes one TSV per query to `output/`
(same base name as the `.sql` file: `output/redmine_issues.tsv`,
`output/redmine_projects.tsv`) — regenerating the exports consumed by
`JstreeJsonBuilder` above.

```bash
./export-redmine-issues.sh
```

Pass one or more `.sql` files as positional arguments to export only those
(each still written to `output/<basename>.tsv`), overriding the
`redmine_issues.sql`/`redmine_projects.sql` default:

```bash
./export-redmine-issues.sh sql/redmine_projects.sql
./export-redmine-issues.sh sql/redmine_issues.sql sql/redmine_projects.sql
```

`--db <path>` and `--out <dir>` override the db properties file and the
output directory (defaults: `etc/db.properties`, `output/`); they can appear
anywhere among the arguments:

```bash
./export-redmine-issues.sh --db /path/to/db.properties --out /path/to/outputDir sql/redmine_projects.sql
```

Connection settings are read from `etc/db.properties` (not committed with
real credentials — fill in `db.user`/`db.password` before running, or point
at another file). Either set `db.url` directly, or `db.host`/`db.port`/
`db.name` to have the JDBC URL composed automatically.

The JDBC driver is vendored in `lib/postgresql-*.jar` (no Maven required);
`java/RedmineIssuesExporter.java` is compiled on first run the same way as
`JstreeJsonBuilder.java`. Output is written in the same RFC4180-style TSV
format `JstreeJsonBuilder.parseTsv` expects (tab-delimited, `"`-quoted
fields for values containing tabs/newlines/quotes).

## Notes on the data

- `redmine-issues.tsv` contains many duplicate rows for the same issue
  (a fan-out from a SQL join in the original export): the tool deduplicates
  by `id`, keeping the first occurrence.
- Projects/issues with a missing or unresolvable `parent_id` are attached to
  the tree root (`parent: "#"`) instead of failing generation.
- The tracker name is read from the joined `t_name` column (index 28 of
  `redmine-issues.tsv`) to decide whether to apply the `A:` prefix to the
  node text.
