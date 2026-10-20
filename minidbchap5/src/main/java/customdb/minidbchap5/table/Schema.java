package customdb.minidbchap5.table;

import java.util.List;

public class Schema {
  private final String tableName;
  private final List<Column> columns;

  public Schema(String tableName, List<Column> columns) {
    this.tableName = tableName;
    this.columns = columns;
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

  public record Column(String name, DataType type, int length) {

    public boolean isIndexed() {
      return name.equalsIgnoreCase("id");
    }
  }

  public enum DataType {
    INTEGER,
    STRING,
    FLOAT,
    DOUBLE
  }
}
