package customdb.chapter08.Operator;

import customdb.chapter08.DB.Row;
import customdb.chapter08.DB.Table;
import customdb.chapter08.Parser.Statement;
import customdb.chapter08.Transaction.Transaction;
import java.io.IOException;
import java.util.Iterator;

public class DeleteOperator implements Operator {
  private final Table table;
  private final Statement.Delete statement;
  private final Transaction transaction;
  private Iterator<Table.Record> iterator;

  public DeleteOperator(Table table, Statement.Delete statement, Transaction transaction) {
    this.table = table;
    this.statement = statement;
    this.transaction = transaction;
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

        // Abort時に削除した行を復元できるように記録する
        transaction.addUndo(() -> table.insert(row));

        return row;
      }
    }

    return null;
  }

  @Override
  public void close() throws IOException {
    this.iterator = null;
  }
}
