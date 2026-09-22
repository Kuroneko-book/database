package customdb.minidb.db;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class Schema {
  private final String tableName;
  private final List<Column> columns;
  private final Column keyColumn;

  public Schema(String tableName, List<Column> columns) {
    this.tableName = identifier(tableName);
    if (columns == null || columns.isEmpty()) {
      throw new IllegalArgumentException("At least one column is required.");
    }

    this.columns = List.copyOf(columns);

    Set<String> names = new HashSet<>();
    Column primary = null;

    for (Column column : columns) {
      if (!names.add(column.name())) {
        throw new IllegalArgumentException("Duplicate column: " + column.name());
      }

      if (column.primaryKey()) {
        if (primary != null) {
          throw new IllegalArgumentException("Exactly one primary key column is required.");
        }
        primary = column;
      }
    }

    if (primary == null) {
      throw new IllegalArgumentException("Exactly one primary key column is required.");
    }

    this.keyColumn = primary;
  }

  public String getTableName() {
    return tableName;
  }

  public List<Column> getColumns() {
    return columns;
  }

  public Column getColumn(String columnName) {
    for (Column column : columns) {
      if (column.name().equalsIgnoreCase(columnName)) {
        return column;
      }
    }

    return null;
  }

  public Column getKeyColumn() {
    return keyColumn;
  }

  public void validate(Row row) {
    if (row == null || row.keySet().size() != columns.size()) {
      throw new IllegalArgumentException("Row does not match schema.");
    }

    for (Column column : columns) {
      Object value = row.get(column.name());
      boolean valid =
          switch (column.type()) {
            case INTEGER -> value instanceof Integer;
            case FLOAT -> value instanceof Float number && Float.isFinite(number);
            case DOUBLE -> value instanceof Double number && Double.isFinite(number);
            case STRING ->
                value instanceof String text
                    && text.getBytes(StandardCharsets.UTF_8).length <= column.length();
          };

      if (!valid) {
        throw new IllegalArgumentException("Invalid value or length for column: " + column.name());
      }
    }
  }

  public String key(Row row) {
    Object value = row.get(keyColumn.name());
    if (value == null) {
      throw new IllegalArgumentException("Primary key is missing: " + keyColumn.name());
    }

    if (value instanceof Float number && number == 0.0f) return "0.0";
    if (value instanceof Double number && number == 0.0d) return "0.0";

    return value.toString();
  }

  public Object parseValue(String raw, Column column) {
    String value = raw;
    if (raw.length() >= 2 && raw.startsWith("'") && raw.endsWith("'")) {
      value = raw.substring(1, raw.length() - 1).replace("''", "'");
    }

    try {
      return switch (column.type()) {
        case INTEGER -> Integer.parseInt(value);
        case FLOAT -> {
          float number = Float.parseFloat(value);
          if (!Float.isFinite(number)) throw new NumberFormatException();
          yield number == 0.0f ? 0.0f : number;
        }
        case DOUBLE -> {
          double number = Double.parseDouble(value);
          if (!Double.isFinite(number)) throw new NumberFormatException();
          yield number == 0.0d ? 0.0d : number;
        }
        case STRING -> {
          if (value.getBytes(StandardCharsets.UTF_8).length > column.length()) {
            throw new IllegalArgumentException(
                "Value exceeds UTF-8 byte limit for column: " + column.name());
          }
          yield value;
        }
      };
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "Invalid " + column.type() + " value for column: " + column.name(), e);
    }
  }

  public Object defaultValue(Column column) {
    return switch (column.type()) {
      case INTEGER -> 0;
      case FLOAT -> 0.0f;
      case DOUBLE -> 0.0d;
      case STRING -> "";
    };
  }

  public static String identifier(String name) {
    if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new IllegalArgumentException("Invalid identifier: " + name);
    }

    return name.toLowerCase(Locale.ROOT);
  }

  public record Column(
      String name, DataType type, int length, boolean primaryKey, boolean indexed) {
    public Column(String name, DataType type, int length, boolean primaryKey) {
      this(name, type, length, primaryKey, false);
    }

    public Column {
      name = identifier(name);
      if (type == null || (type == DataType.STRING ? length <= 0 : length != 0)) {
        throw new IllegalArgumentException("Invalid type or length for column: " + name);
      }
      if (indexed && type != DataType.INTEGER) {
        throw new IllegalArgumentException("Only INTEGER columns can be indexed: " + name);
      }
    }
  }

  public enum DataType {
    INTEGER,
    STRING,
    FLOAT,
    DOUBLE
  }
}
