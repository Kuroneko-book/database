package customdb.minidbchap6.table;

import java.util.List;

public class Schema {
  private final String tableName;
  private final List<Column> columns;
  private final Column primaryKeyColumn;

  public Schema(String tableName, List<Column> columns) {
    this.tableName = tableName;
    this.columns = columns;
    Column primaryKey = null;
    for (Column column : columns) {
      if (column.primaryKey()) {
        if (primaryKey != null) {
          throw new IllegalArgumentException("Only one PRIMARY KEY column is supported.");
        }
        primaryKey = column;
      }
    }
    if (primaryKey == null) {
      throw new IllegalArgumentException("PRIMARY KEY is required for table: " + tableName);
    }
    this.primaryKeyColumn = primaryKey;
  }

  public String getTableName() {
    return tableName;
  }

  public List<Column> getColumns() {
    return columns;
  }

  public Column primaryKeyColumn() {
    return primaryKeyColumn;
  }

  public Column getColumn(String columnName) {
    for (Column column : columns) {
      if (column.name().equalsIgnoreCase(columnName)) {
        return column;
      }
    }
    return null;
  }

  public record Column(
      String name, DataType type, int length, boolean primaryKey, boolean indexed) {

    public Column(String name, DataType type, int length) {
      this(name, type, length, false, false);
    }

    public Column(String name, DataType type, int length, boolean primaryKey) {
      this(name, type, length, primaryKey, primaryKey);
    }

    public boolean isIndexed() {
      return primaryKey || indexed;
    }
  }

  public enum DataType {
    INTEGER,
    STRING,
    FLOAT,
    DOUBLE
  }
}
