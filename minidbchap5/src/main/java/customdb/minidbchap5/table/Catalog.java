package customdb.minidbchap5.table;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class Catalog {
  private static final String CATALOG_FILE = "catalog.txt";

  private final Path baseDir;
  private final Path catalogPath;

  private final Map<String, Schema> schemas = new HashMap<>();
  private final Map<String, Table> tables = new HashMap<>();

  public Catalog(Path baseDir) throws IOException {
    this.baseDir = baseDir;
    this.catalogPath = baseDir.resolve(CATALOG_FILE);

    if (!Files.exists(baseDir)) {
      Files.createDirectories(baseDir);
    }

    load();
  }

  public void createTable(Schema schema) throws IOException {
    String tableName = normalize(schema.getTableName());

    if (schemas.containsKey(tableName)) {
      throw new IllegalArgumentException("Table already exists: " + schema.getTableName());
    }

    for (Schema.Column column : schema.getColumns()) {
      validateIdentifier("Column name", column.name());
    }

    Path tablePath = getTablePath(tableName);

    // カタログに無いファイルの確認
    if (Files.exists(tablePath)) {
      throw new IllegalStateException(
          "Data file already exists for new table: " + tablePath.getFileName());
    }

    Table table = new Table(schema, tablePath);

    schemas.put(tableName, schema);
    tables.put(tableName, table);

    save();
  }

  public Schema getSchema(String tableName) {
    return schemas.get(normalize(tableName));
  }

  public Table getTable(String tableName) {
    return tables.get(normalize(tableName));
  }

  public Schema requireSchema(String tableName) {
    Schema schema = getSchema(tableName);

    if (schema == null) {
      throw new IllegalArgumentException("Table does not exist: " + tableName);
    }

    return schema;
  }

  public Table requireTable(String tableName) {
    Table table = getTable(tableName);

    if (table == null) {
      throw new IllegalArgumentException("Table does not exist: " + tableName);
    }

    return table;
  }

  public void close() throws IOException {
    IOException firstException = null;

    for (Table table : tables.values()) {
      try {
        table.close();
      } catch (IOException e) {
        if (firstException == null) {
          firstException = e;
        } else {
          firstException.addSuppressed(e);
        }
      }
    }

    if (firstException != null) {
      throw firstException;
    }
  }

  private void load() throws IOException {
    if (!Files.exists(catalogPath)) {
      return;
    }

    try (BufferedReader reader = Files.newBufferedReader(catalogPath, StandardCharsets.UTF_8)) {
      String line;

      while ((line = reader.readLine()) != null) {
        line = line.trim();

        if (line.isEmpty()) {
          continue;
        }

        Schema schema = parseSchemaLine(line);
        String tableName = normalize(schema.getTableName());

        schemas.put(tableName, schema);

        // ファイルがある場合のみデータ登録
        Path tablePath = getTablePath(tableName);
        if (Files.exists(tablePath)) {
          tables.put(tableName, new Table(schema, tablePath));
        }
      }
    }
  }

  private void save() throws IOException {
    if (!Files.exists(baseDir)) {
      Files.createDirectories(baseDir);
    }

    Path tempPath = baseDir.resolve(CATALOG_FILE + ".tmp");
    try (BufferedWriter writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
      for (Schema schema : schemas.values()) {
        writer.write(toSchemaLine(schema));
        writer.newLine();
      }
    }
    // 書き込み完了後にアトミックにリネームして上書き
    Files.move(
        tempPath, catalogPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private String toSchemaLine(Schema schema) {
    List<String> columnTexts = new ArrayList<>();

    for (Schema.Column column : schema.getColumns()) {
      columnTexts.add(
          column.name()
              + ":"
              + column.type().name()
              + ":"
              + column.length()
              + ":"
              + (column.primaryKey() ? "1" : "0"));
    }

    return schema.getTableName() + "|" + String.join(",", columnTexts);
  }

  private Schema parseSchemaLine(String line) {
    String[] parts = line.split("\\|", 2);

    if (parts.length != 2) {
      throw new IllegalArgumentException("Invalid catalog line: " + line);
    }

    String tableName = parts[0];
    String columnsPart = parts[1];

    List<Schema.Column> columns = new ArrayList<>();

    if (!columnsPart.isBlank()) {
      String[] columnTexts = columnsPart.split(",");

      for (String columnText : columnTexts) {
        String[] columnParts = columnText.split(":", 4);

        if (columnParts.length != 4) {
          throw new IllegalArgumentException("Invalid column definition: " + columnText);
        }

        String columnName = columnParts[0];

        validateIdentifier("Table name", tableName);
        validateIdentifier("Column name", columnName);

        Schema.DataType type = Schema.DataType.valueOf(columnParts[1].toUpperCase(Locale.ROOT));
        int length = Integer.parseInt(columnParts[2]);
        if (!columnParts[3].equals("0") && !columnParts[3].equals("1")) {
          throw new IllegalArgumentException("Invalid primary key flag: " + columnText);
        }

        columns.add(new Schema.Column(columnName, type, length, columnParts[3].equals("1")));
      }
    }

    validateIdentifier("Table name", tableName);

    return new Schema(tableName, columns);
  }

  private Path getTablePath(String tableName) {
    return baseDir.resolve(normalize(tableName) + ".db");
  }

  private String normalize(String tableName) {
    validateIdentifier("Table name", tableName);
    return tableName.toLowerCase(Locale.ROOT);
  }

  // 有効な名前かチェックするメソッドを追加
  private void validateIdentifier(String kind, String identifier) {
    if (identifier == null
        || !Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(identifier).matches()) {
      throw new IllegalArgumentException(
          kind + " must be a safe ASCII identifier [A-Za-z_][A-Za-z0-9_]*: " + identifier);
    }
  }
}
