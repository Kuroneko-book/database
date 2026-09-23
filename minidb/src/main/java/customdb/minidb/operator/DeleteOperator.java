package customdb.minidb.operator;

import customdb.minidb.db.Row;
import customdb.minidb.db.Schema;
import customdb.minidb.db.Table;
import customdb.minidb.parser.Statement;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

public class DeleteOperator implements Operator {
  private final Table table;
  private final Schema schema;
  private final ConditionEvaluator.BoundCondition condition;
  private Iterator<Table.Record> iterator;

  public DeleteOperator(Table table, Schema schema, Statement.Delete statement) {
    this.table = table;
    this.schema = schema;
    this.condition = ConditionEvaluator.bindCondition(List.of(schema), statement.whereCondition());
  }

  @Override
  public void open() throws IOException {
    iterator = null;
    iterator = table.scanRecords().iterator();
  }

  @Override
  public Row next() throws IOException {
    if (iterator == null) return null;

    while (iterator.hasNext()) {
      Table.Record record = iterator.next();
      Row row = record.row();
      if (ConditionEvaluator.matches(ConditionEvaluator.qualifyRow(schema, row), condition)) {
        table.delete(record.key());
        return row;
      }
    }

    return null;
  }

  @Override
  public void close() {
    iterator = null;
  }
}
