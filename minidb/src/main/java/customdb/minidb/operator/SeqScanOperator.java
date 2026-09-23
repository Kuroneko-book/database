package customdb.minidb.operator;

import customdb.minidb.db.Row;
import customdb.minidb.db.Schema;
import customdb.minidb.db.Table;
import java.io.IOException;
import java.util.Iterator;

public class SeqScanOperator implements Operator {
  private final Table table;
  private final Schema schema;
  private Iterator<Row> iterator;

  public SeqScanOperator(Table table, Schema schema) {
    this.table = table;
    this.schema = schema;
  }

  @Override
  public void open() throws IOException {
    iterator = null;
    iterator = table.scan().iterator();
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
    return "SeqScan : " + schema.getTableName();
  }
}
