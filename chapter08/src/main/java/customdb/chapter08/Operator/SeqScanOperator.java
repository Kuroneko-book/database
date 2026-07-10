package customdb.chapter08.Operator;

import customdb.chapter08.DB.Row;
import customdb.chapter08.DB.Schema;
import customdb.chapter08.DB.Storage;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

public class SeqScanOperator implements Operator {
  private final Storage storage;
  private final Schema schema;
  private final String tableName;
  private Iterator<Row> iterator;

  public SeqScanOperator(Storage storage, Schema schema, String tableName) {
    this.storage = storage;
    this.schema = schema;
    this.tableName = tableName;
  }

  @Override
  public void open() throws IOException {
    List<Row> rows = storage.scan();
    this.iterator = rows.iterator();
  }

  @Override
  public Row next() throws IOException {
    if (iterator != null && iterator.hasNext()) {
      Row rawRow = iterator.next();
      return qualifyRow(tableName, schema, rawRow);
    }
    return null;
  }

  @Override
  public void close() throws IOException {
    this.iterator = null;
  }

  private Row qualifyRow(String tableName, Schema schema, Row row) {
    Row qualified = new Row();
    for (Schema.Column column : schema.getColumns()) {
      String columnName = column.name();
      Object value = row.get(columnName);
      qualified.put(columnName, value);
      qualified.put(tableName + "." + columnName, value);
    }
    return qualified;
  }
}
