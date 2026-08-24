import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the Redmine projects/issues TSV exports and produces a flat jsTree
 * JSON file ({id, parent, text, icon}), with folder icons for projects and
 * file icons for issues.
 *
 * Usage:
 *   javac JstreeJsonBuilder.java
 *   java JstreeJsonBuilder [--prune-empty-projects] [projectsTsv] [issuesTsv] [outputJson]
 *
 * Defaults:
 *   projectsTsv = ../sql/redmine_production_projects.tsv
 *   issuesTsv   = ../sql/redmine-issues.tsv
 *   outputJson  = ../jstree-projects-issues.json
 *
 * --prune-empty-projects (off by default): drop projects that have neither
 *   a direct issue nor a surviving child project — i.e. branches with no
 *   issues anywhere in them. May appear anywhere among the arguments.
 */
public class JstreeJsonBuilder {

    // Issues TSV column positions (the header has duplicate/prefixed names
    // coming from joined lookup tables, so we address these by fixed index
    // instead of by name).
    private static final int ISSUE_COL_ID = 0;
    private static final int ISSUE_COL_PROJECT_ID = 2;
    private static final int ISSUE_COL_SUBJECT = 3;
    private static final int ISSUE_COL_PARENT_ID = 17;
    private static final int ISSUE_COL_TRACKER_NAME = 28; // joined tracker.name (t_name)

    private static final String TRACKER_AREA = "Area";

    record Project(String id, String name, String parentId) {}

    record Issue(String id, String projectId, String subject, String parentId, String trackerName) {}

    record Node(String id, String parent, String text, String icon) {}

    private static final String FLAG_PRUNE = "--prune-empty-projects";

    public static void main(String[] args) throws IOException {
        boolean pruneEmptyProjects = false;
        List<String> positional = new ArrayList<>();
        for (String a : args) {
            if (FLAG_PRUNE.equals(a)) {
                pruneEmptyProjects = true;
            } else {
                positional.add(a);
            }
        }

        Path javaDir = classDirectory();
        Path defaultProjects = javaDir.resolve("../sql/redmine_production_projects.tsv").normalize();
        Path defaultIssues = javaDir.resolve("../sql/redmine-issues.tsv").normalize();
        Path defaultOutput = javaDir.resolve("../jstree-projects-issues.json").normalize();

        Path projectsTsv = positional.size() > 0 ? Path.of(positional.get(0)) : defaultProjects;
        Path issuesTsv = positional.size() > 1 ? Path.of(positional.get(1)) : defaultIssues;
        Path outputJson = positional.size() > 2 ? Path.of(positional.get(2)) : defaultOutput;

        Map<String, Project> projects = readProjects(projectsTsv);
        Map<String, Issue> issues = readIssues(issuesTsv);

        List<Node> nodes = buildNodes(projects, issues, pruneEmptyProjects);

        writeJson(nodes, outputJson);

        long keptProjectNodes = nodes.stream().filter(n -> n.id().startsWith("p_")).count();
        System.out.println("projects: " + projects.size()
                + " (kept: " + keptProjectNodes + ", pruned empty: " + (projects.size() - keptProjectNodes)
                + (pruneEmptyProjects ? "" : " — pruning disabled, pass " + FLAG_PRUNE + " to enable") + ")");
        System.out.println("issues:   " + issues.size());
        System.out.println("nodes written: " + nodes.size());
        System.out.println("output: " + outputJson.toAbsolutePath());
    }

    /**
     * Directory containing this class's .class file (i.e. the -cp entry it
     * was loaded from), used to resolve the default TSV/JSON paths so they
     * work regardless of the caller's current working directory. Falls back
     * to user.dir if the code source can't be resolved (e.g. custom loaders).
     */
    private static Path classDirectory() {
        try {
            Path location = Path.of(
                    JstreeJsonBuilder.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(location) ? location : location.getParent();
        } catch (URISyntaxException | NullPointerException e) {
            return Path.of(System.getProperty("user.dir"));
        }
    }

    // ── Readers ──────────────────────────────────────────────────────────

    private static Map<String, Project> readProjects(Path path) throws IOException {
        List<String[]> rows = parseTsv(path);
        String[] header = rows.get(0);
        int idIdx = indexOf(header, "id");
        int nameIdx = indexOf(header, "name");
        int parentIdx = indexOf(header, "parent_id");

        Map<String, Project> projects = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String id = get(row, idIdx).trim();
            if (id.isEmpty()) continue;
            String name = get(row, nameIdx).trim();
            String parent = get(row, parentIdx).trim();
            projects.put(id, new Project(id, name, parent.isEmpty() ? null : parent));
        }
        return projects;
    }

    private static Map<String, Issue> readIssues(Path path) throws IOException {
        List<String[]> rows = parseTsv(path);
        String[] header = rows.get(0);
        if (!"id".equals(header[ISSUE_COL_ID])
                || !"project_id".equals(header[ISSUE_COL_PROJECT_ID])
                || !"subject".equals(header[ISSUE_COL_SUBJECT])
                || !"parent_id".equals(header[ISSUE_COL_PARENT_ID])) {
            throw new IllegalStateException(
                    "Unexpected issues.tsv column layout — fixed column indices no longer match the header");
        }

        // The export duplicates each issue across many rows (fan-out join);
        // a LinkedHashMap keyed by issue id de-duplicates while keeping the
        // first-seen order.
        Map<String, Issue> issues = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String id = get(row, ISSUE_COL_ID).trim();
            if (id.isEmpty()) continue;
            String projectId = get(row, ISSUE_COL_PROJECT_ID).trim();
            String subject = get(row, ISSUE_COL_SUBJECT).trim();
            String parentId = get(row, ISSUE_COL_PARENT_ID).trim();
            String trackerName = get(row, ISSUE_COL_TRACKER_NAME).trim();
            issues.put(id, new Issue(
                    id,
                    projectId.isEmpty() ? null : projectId,
                    subject,
                    parentId.isEmpty() ? null : parentId,
                    trackerName));
        }
        return issues;
    }

    private static String get(String[] row, int idx) {
        return idx >= 0 && idx < row.length ? row[idx] : "";
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].equals(name)) return i;
        }
        throw new IllegalStateException("Column not found: " + name);
    }

    // ── Tree building ────────────────────────────────────────────────────

    private static List<Node> buildNodes(Map<String, Project> projects, Map<String, Issue> issues, boolean pruneEmptyProjects) {
        Set<String> keptProjects = pruneEmptyProjects ? pruneEmptyProjects(projects, issues) : projects.keySet();

        List<Node> nodes = new ArrayList<>();

        for (Project p : projects.values()) {
            if (!keptProjects.contains(p.id())) continue;
            String parentNode = (p.parentId() != null && keptProjects.contains(p.parentId()))
                    ? "p_" + p.parentId()
                    : "#";
            String projectText = p.name().isEmpty() ? "Project #" + p.id() : p.name();
            String text = "P: " + projectText;
            nodes.add(new Node("p_" + p.id(), parentNode, text, "jstree-folder"));
        }

        for (Issue it : issues.values()) {
            String parentNode;
            if (it.parentId() != null && issues.containsKey(it.parentId())) {
                parentNode = "i_" + it.parentId();
            } else if (it.projectId() != null && keptProjects.contains(it.projectId())) {
                parentNode = "p_" + it.projectId();
            } else {
                parentNode = "#";
            }
            String text = TRACKER_AREA.equalsIgnoreCase(it.trackerName())
                    ? ("A: " + it.subject()).trim()
                    : ("#" + it.id() + " " + it.subject()).trim();
            nodes.add(new Node("i_" + it.id(), parentNode, text, "jstree-file"));
        }

        return nodes;
    }

    /**
     * A project is kept if it has at least one issue directly attached to it,
     * or at least one kept child project — i.e. empty branches (no issues
     * anywhere in the subtree) are pruned, propagating up: removing an empty
     * leaf can turn its own parent into a new empty leaf, which is also
     * removed.
     */
    private static Set<String> pruneEmptyProjects(Map<String, Project> projects, Map<String, Issue> issues) {
        Map<String, List<String>> childrenOf = new LinkedHashMap<>();
        for (Project p : projects.values()) {
            if (p.parentId() != null && projects.containsKey(p.parentId())) {
                childrenOf.computeIfAbsent(p.parentId(), k -> new ArrayList<>()).add(p.id());
            }
        }

        Set<String> projectsWithDirectIssue = new java.util.HashSet<>();
        for (Issue it : issues.values()) {
            if (it.projectId() != null && projects.containsKey(it.projectId())) {
                projectsWithDirectIssue.add(it.projectId());
            }
        }

        Map<String, Boolean> memo = new java.util.HashMap<>();
        Set<String> kept = new java.util.LinkedHashSet<>();
        for (String pid : projects.keySet()) {
            if (survives(pid, childrenOf, projectsWithDirectIssue, memo, new java.util.HashSet<>())) {
                kept.add(pid);
            }
        }
        return kept;
    }

    private static boolean survives(
            String pid,
            Map<String, List<String>> childrenOf,
            Set<String> projectsWithDirectIssue,
            Map<String, Boolean> memo,
            Set<String> inProgress) {
        Boolean cached = memo.get(pid);
        if (cached != null) return cached;
        if (!inProgress.add(pid)) return false; // cycle guard
        boolean result = projectsWithDirectIssue.contains(pid)
                || childrenOf.getOrDefault(pid, List.of()).stream()
                        .anyMatch(c -> survives(c, childrenOf, projectsWithDirectIssue, memo, inProgress));
        inProgress.remove(pid);
        memo.put(pid, result);
        return result;
    }

    // ── TSV parsing (RFC4180-style, tab-delimited, quote char '"') ─────────
    //
    // Fields containing tabs, newlines or quotes are wrapped in double
    // quotes; a doubled quote ("") inside a quoted field is a literal
    // quote. Handles embedded \r\n within quoted multi-line fields.

    private static List<String[]> parseTsv(Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        List<String[]> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();

        boolean inQuotes = false;
        int i = 0;
        int len = content.length();
        while (i < len) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    } else {
                        inQuotes = false;
                        i++;
                        continue;
                    }
                } else {
                    field.append(c);
                    i++;
                    continue;
                }
            } else {
                if (c == '"' && field.length() == 0) {
                    inQuotes = true;
                    i++;
                    continue;
                } else if (c == '\t') {
                    fields.add(field.toString());
                    field.setLength(0);
                    i++;
                    continue;
                } else if (c == '\n') {
                    fields.add(field.toString());
                    field.setLength(0);
                    rows.add(fields.toArray(new String[0]));
                    fields.clear();
                    i++;
                    continue;
                } else if (c == '\r') {
                    // bare CR outside quotes: ignore (part of CRLF line ending)
                    i++;
                    continue;
                } else {
                    field.append(c);
                    i++;
                    continue;
                }
            }
        }
        // last field/row if file doesn't end with newline
        if (field.length() > 0 || !fields.isEmpty()) {
            fields.add(field.toString());
            rows.add(fields.toArray(new String[0]));
        }
        return rows;
    }

    // ── JSON writing ─────────────────────────────────────────────────────

    private static void writeJson(List<Node> nodes, Path outputJson) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            sb.append("  {\n");
            sb.append("    \"id\": ").append(jsonString(n.id())).append(",\n");
            sb.append("    \"parent\": ").append(jsonString(n.parent())).append(",\n");
            sb.append("    \"text\": ").append(jsonString(n.text())).append(",\n");
            sb.append("    \"icon\": ").append(jsonString(n.icon())).append("\n");
            sb.append("  }");
            if (i < nodes.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]\n");
        Files.writeString(outputJson, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String jsonString(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append("\"");
        return out.toString();
    }
}
