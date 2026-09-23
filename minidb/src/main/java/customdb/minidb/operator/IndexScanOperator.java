package customdb.minidb.operator;

import customdb.minidb.db.Row;
import customdb.minidb.db.Schema;
import customdb.minidb.db.Table;
import java.io.IOException;
import java.util.Iterator;

public class IndexScanOperator implements Operator {
  private final Table table;
  private final Schema schema;
  private final String columnName;
  private final int value;
  private Iterator<Row> iterator;

  public IndexScanOperator(Table table, Schema schema, String columnName, int value) {
    this.table = table;
    this.schema = schema;
    this.columnName = columnName;
    this.value = value;
  }

  @Override
  public void open() throws IOException {
    iterator = null;
    iterator = table.searchByIndex(columnName, value).iterator();
  }

  @Override
  public Row next() throws IOException {
    if (iterator == null || !iterator.hasNext()) return null;

    return ConditionEvaluator.qualifyRow(schema, iterator.next());
  }

  @Override
  public void close() throws IOException {
    iterator = null;
  }

  @Override
  public String toString() {
    return "IndexScan : " + schema.getTableName();
  }
}
