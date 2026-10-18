package customdb.minidbchap8.Operator;

import customdb.minidbchap8.DB.Row;
import customdb.minidbchap8.DB.Table;
import customdb.minidbchap8.Parser.Statement;
import customdb.minidbchap8.Transaction.Transaction;
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
