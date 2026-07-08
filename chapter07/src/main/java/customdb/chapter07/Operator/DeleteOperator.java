package customdb.chapter07.Operator;

import customdb.chapter07.DB.Row;
import customdb.chapter07.DB.Table;
import customdb.chapter07.Parser.Statement;
import java.io.IOException;
import java.util.Iterator;

public class DeleteOperator implements Operator {
  private final Table table;
  private final Statement.Delete statement;
  private Iterator<Table.Record> iterator;
  private boolean mutated = false;

  public DeleteOperator(Table table, Statement.Delete statement) {
    this.table = table;
    this.statement = statement;
  }

  @Override
  public void open() throws IOException {
    this.iterator = table.scanRecords().iterator();
  }

  @Override
  public Row next() throws IOException {
    while (iterator != null && iterator.hasNext()) {
      Table.Record record = iterator.next();
      Row row = record.row();

      if (ConditionEvaluator.matches(row, statement.whereCondition())) {
        table.delete(record.recordId());
        mutated = true;
        return row;
      }
    }

    return null;
  }

  @Override
  public void close() throws IOException {
    // 削除が発生した場合はインデックスを最新の状態に作り直す
    if (mutated) {
      table.rebuildIndexes();
    }
    this.iterator = null;
  }
}
