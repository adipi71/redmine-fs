import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Si connette a un database PostgreSQL, esegue una o più query lette da file
 * .sql in sql/ (default: redmine_issues.sql e redmine_projects.sql) e ne
 * esporta il risultato in formato TSV (RFC4180-style, delimitatore tab,
 * quoting con '"') nella directory output/, un file per query, con lo stesso
 * nome base del file .sql.
 *
 * Usage:
 *   javac -cp ../lib/postgresql-42.7.4.jar RedmineIssuesExporter.java
 *   java -cp .:../lib/postgresql-42.7.4.jar RedmineIssuesExporter [--db dbProperties] [--out outputDir] [sqlFile...]
 *
 * Defaults (risolti rispetto alla directory del .class, non alla cwd):
 *   --db  = ../etc/db.properties
 *   --out = ../output
 *   sqlFile... = ../sql/redmine_issues.sql ../sql/redmine_projects.sql (se nessun file è passato da riga di comando)
 *
 * Il file di properties supporta db.url (usato così com'è se presente) oppure
 * db.host/db.port/db.name/db.user/db.password per comporre l'URL JDBC.
 */
public class RedmineIssuesExporter {

    private static final int FETCH_SIZE = 1000;

    public static void main(String[] args) throws IOException, SQLException {
        Path javaDir = classDirectory();
        Path propertiesFile = javaDir.resolve("../etc/db.properties").normalize();
        Path outputDir = javaDir.resolve("../output").normalize();
        Path sqlDir = javaDir.resolve("../sql").normalize();

        List<Path> sqlFiles = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--db" -> propertiesFile = Path.of(requireArg(args, ++i, "--db"));
                case "--out" -> outputDir = Path.of(requireArg(args, ++i, "--out"));
                default -> sqlFiles.add(Path.of(args[i]));
            }
        }
        if (sqlFiles.isEmpty()) {
            sqlFiles.add(sqlDir.resolve("redmine_issues.sql"));
            sqlFiles.add(sqlDir.resolve("redmine_projects.sql"));
        }

        Properties dbProps = loadProperties(propertiesFile);
        String jdbcUrl = buildJdbcUrl(dbProps);
        Properties connectionProps = buildConnectionProperties(dbProps);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, connectionProps)) {
            conn.setAutoCommit(false); // richiesto da PostgreSQL perché setFetchSize esegua lo streaming lato server
            for (Path sqlFile : sqlFiles) {
                Path outputTsv = outputDir.resolve(tsvFileName(sqlFile));
                String sql = stripTrailingSemicolon(Files.readString(sqlFile, StandardCharsets.UTF_8).trim());

                long rows;
                try (Statement stmt = conn.createStatement()) {
                    stmt.setFetchSize(FETCH_SIZE);
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        rows = writeTsv(rs, outputTsv);
                    }
                }

                System.out.println(sqlFile.getFileName() + " -> " + outputTsv.toAbsolutePath()
                        + " (righe esportate: " + rows + ")");
            }
        }
    }

    private static String requireArg(String[] args, int idx, String flag) {
        if (idx >= args.length) {
            throw new IllegalArgumentException(flag + " richiede un valore");
        }
        return args[idx];
    }

    private static String tsvFileName(Path sqlFile) {
        String name = sqlFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String baseName = dot > 0 ? name.substring(0, dot) : name;
        return baseName + ".tsv";
    }

    // ── Config ───────────────────────────────────────────────────────────

    private static Properties loadProperties(Path path) throws IOException {
        Properties props = new Properties();
        try (var in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(in);
        }
        return props;
    }

    private static String buildJdbcUrl(Properties dbProps) {
        String url = dbProps.getProperty("db.url");
        if (url != null && !url.isBlank()) {
            return url.trim();
        }
        String host = dbProps.getProperty("db.host", "localhost").trim();
        String port = dbProps.getProperty("db.port", "5432").trim();
        String name = dbProps.getProperty("db.name", "").trim();
        if (name.isEmpty()) {
            throw new IllegalStateException("db.properties: specificare db.url oppure db.name");
        }
        return "jdbc:postgresql://" + host + ":" + port + "/" + name;
    }

    private static Properties buildConnectionProperties(Properties dbProps) {
        Properties connectionProps = new Properties();
        String user = dbProps.getProperty("db.user");
        if (user != null && !user.isBlank()) {
            connectionProps.setProperty("user", user.trim());
        }
        String password = dbProps.getProperty("db.password");
        if (password != null && !password.isEmpty()) {
            connectionProps.setProperty("password", password);
        }
        return connectionProps;
    }

    private static String stripTrailingSemicolon(String sql) {
        return sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
    }

    /**
     * Directory contenente il .class di questa classe, usata per risolvere i
     * path di default indipendentemente dalla cwd da cui viene lanciato lo
     * script. Fallback su user.dir se il code source non è risolvibile.
     */
    private static Path classDirectory() {
        try {
            Path location = Path.of(
                    RedmineIssuesExporter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(location) ? location : location.getParent();
        } catch (URISyntaxException | NullPointerException e) {
            return Path.of(System.getProperty("user.dir"));
        }
    }

    // ── TSV export ───────────────────────────────────────────────────────
    //
    // RFC4180-style, delimitatore tab, quote char '"': un campo che contiene
    // tab, newline, CR o '"' viene racchiuso tra doppi apici, con '"' interni
    // raddoppiati. Stesso formato letto da JstreeJsonBuilder.parseTsv.

    private static long writeTsv(ResultSet rs, Path outputTsv) throws SQLException, IOException {
        Files.createDirectories(outputTsv.toAbsolutePath().getParent());

        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        long rowCount = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(outputTsv, StandardCharsets.UTF_8)) {
            for (int i = 1; i <= columnCount; i++) {
                if (i > 1) writer.write('\t');
                writer.write(escapeTsvField(meta.getColumnLabel(i)));
            }
            writer.write('\n');

            while (rs.next()) {
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) writer.write('\t');
                    String value = rs.getString(i);
                    writer.write(escapeTsvField(value == null ? "" : value));
                }
                writer.write('\n');
                rowCount++;
            }
        }
        return rowCount;
    }

    private static String escapeTsvField(String value) {
        boolean needsQuoting = value.indexOf('\t') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('"') >= 0;
        if (!needsQuoting) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
