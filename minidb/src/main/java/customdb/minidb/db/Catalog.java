package customdb.minidb.db;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Catalog implements AutoCloseable {
  private final Path baseDir;
  private final Path catalogPath;
  private final Map<String, Schema> schemas;
  private final Map<String, Table> tables;

  public Catalog(Path baseDir) throws IOException {
    this.baseDir = baseDir;
    this.catalogPath = baseDir.resolve("catalog.txt");
    this.schemas = new LinkedHashMap<>();
    this.tables = new LinkedHashMap<>();

    Files.createDirectories(baseDir);

    try {
      load();
    } catch (IOException | RuntimeException e) {
      try {
        close();
      } catch (IOException closeError) {
        e.addSuppressed(closeError);
      }
      throw e;
    }
  }

  public void createTable(Schema schema) throws IOException {
    String tableName = schema.getTableName();
    if (schemas.containsKey(tableName)) {
      throw new IllegalArgumentException("Table already exists: " + tableName);
    }

    Path path = getTablePath(tableName);
    Files.createFile(path);

    Table table = null;

    try {
      table = new Table(schema, path);
      Map<String, Schema> updated = new LinkedHashMap<>(schemas);
      updated.put(tableName, schema);
      save(updated);
    } catch (IOException | RuntimeException e) {
      if (table != null) {
        try {
          table.close();
        } catch (IOException closeError) {
          e.addSuppressed(closeError);
        }
      }
      try {
        Files.deleteIfExists(path);
      } catch (IOException deleteError) {
        e.addSuppressed(deleteError);
      }
      throw e;
    }

    schemas.put(tableName, schema);
    tables.put(tableName, table);
  }

  public Schema getSchema(String tableName) {
    return schemas.get(Schema.identifier(tableName));
  }

  public Table getTable(String tableName) {
    return tables.get(Schema.identifier(tableName));
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

  private void load() throws IOException {
    if (!Files.exists(catalogPath)) return;

    for (String line : Files.readAllLines(catalogPath, StandardCharsets.UTF_8)) {
      if (line.isBlank()) continue;

      Schema schema;
      try {
        schema = parseSchemaLine(line);
      } catch (IllegalArgumentException e) {
        throw new IOException("Invalid catalog entry: " + line, e);
      }

      String tableName = schema.getTableName();
      if (schemas.putIfAbsent(tableName, schema) != null) {
        throw new IOException("Duplicate table in catalog: " + tableName);
      }
    }

    for (Schema schema : schemas.values()) {
      Path path = getTablePath(schema.getTableName());
      if (!Files.isRegularFile(path)) {
        throw new IOException("Table file is missing: " + path);
      }

      tables.put(schema.getTableName(), new Table(schema, path));
    }
  }

  private void save(Map<String, Schema> updated) throws IOException {
    Path tempPath = Files.createTempFile(baseDir, "catalog-", ".tmp");

    try {
      try (BufferedWriter writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
        for (Schema schema : updated.values()) {
          writer.write(toSchemaLine(schema));
          writer.newLine();
        }
      }

      Files.move(
          tempPath,
          catalogPath,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(tempPath);
    }
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
              + column.primaryKey()
              + ":"
              + column.indexed());
    }

    return schema.getTableName() + "|" + String.join(",", columnTexts);
  }

  private Schema parseSchemaLine(String line) {
    String[] parts = line.split("\\|", -1);
    if (parts.length != 2) throw new IllegalArgumentException("Invalid catalog entry.");

    List<Schema.Column> columns = new ArrayList<>();
    for (String columnText : parts[1].split(",", -1)) {
      String[] values = columnText.split(":", -1);
      if ((values.length != 4 && values.length != 5)
          || !(values[3].equals("true") || values[3].equals("false"))
          || (values.length == 5 && !(values[4].equals("true") || values[4].equals("false")))) {
        throw new IllegalArgumentException("Invalid column entry.");
      }

      columns.add(
          new Schema.Column(
              values[0],
              Schema.DataType.valueOf(values[1]),
              Integer.parseInt(values[2]),
              Boolean.parseBoolean(values[3]),
              values.length == 5 && Boolean.parseBoolean(values[4])));
    }

    return new Schema(parts[0], columns);
  }

  private Path getTablePath(String tableName) {
    return baseDir.resolve(Schema.identifier(tableName) + ".tbl");
  }

  @Override
  public void close() throws IOException {
    IOException firstException = null;
    for (Table table : tables.values()) {
      try {
        table.close();
      } catch (IOException e) {
        if (firstException == null) firstException = e;
        else firstException.addSuppressed(e);
      }
    }

    if (firstException != null) throw firstException;
  }
}
