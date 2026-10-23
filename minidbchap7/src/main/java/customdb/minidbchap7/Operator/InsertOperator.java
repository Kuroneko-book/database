package customdb.minidbchap7.operator;

import customdb.minidbchap7.parser.Statement;
import customdb.minidbchap7.table.Row;
import customdb.minidbchap7.table.Schema;
import customdb.minidbchap7.table.Table;
import java.io.IOException;
import java.util.List;

public class InsertOperator implements Operator {
  private final Table table;
  private final Schema schema;
  private final Statement.Insert statement;
  private boolean executed = false;

  public InsertOperator(Table table, Schema schema, Statement.Insert statement) {
    this.table = table;
    this.schema = schema;
    this.statement = statement;
  }

  @Override
  public void open() throws IOException {}

  @Override
  public Row next() throws IOException {
    if (executed) {
      return null;
    }
    Row row = buildRow(schema, statement);
    table.insert(row);
    executed = true;
    return row;
  }

  @Override
  public void close() throws IOException {}

  private Row buildRow(Schema schema, Statement.Insert statement) {
    Row row = new Row();

    for (Schema.Column column : schema.getColumns()) {
      row.put(column.name(), ValueParser.defaultValue(column));
    }

    List<String> columnNames = statement.columnNames();
    List<String> values = statement.values();

    Schema.Column primaryKey = schema.primaryKeyColumn();
    if (!columnNames.isEmpty()) {
      // 列を指定するINSERTでは主キーが必須
      boolean supplied = false;
      for (String columnName : columnNames) {
        if (columnName.equalsIgnoreCase(primaryKey.name())) {
          supplied = true;
          break;
        }
      }
      if (!supplied) {
        throw new IllegalArgumentException(
            "Primary key column must be specified: " + primaryKey.name());
      }
    }

    if (columnNames.isEmpty()) {
      if (values.size() != schema.getColumns().size()) {
        throw new IllegalArgumentException(
            "Column count does not match value count. columns="
                + schema.getColumns().size()
                + ", values="
                + values.size());
      }

      for (int i = 0; i < schema.getColumns().size(); i++) {
        Schema.Column column = schema.getColumns().get(i);
        row.put(column.name(), ValueParser.parseValue(values.get(i), column));
      }

      return row;
    }

    if (columnNames.size() != values.size()) {
      throw new IllegalArgumentException(
          "Column count does not match value count. columns="
              + columnNames.size()
              + ", values="
              + values.size());
    }

    for (int i = 0; i < columnNames.size(); i++) {
      String columnName = columnNames.get(i);
      Schema.Column column = schema.getColumn(columnName);

      if (column == null) {
        throw new IllegalArgumentException("Unknown column: " + columnName);
      }

      row.put(column.name(), ValueParser.parseValue(values.get(i), column));
    }

    return row;
  }
}
