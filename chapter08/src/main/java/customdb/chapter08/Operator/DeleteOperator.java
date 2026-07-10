package customdb.chapter08.Operator;

import customdb.chapter08.DB.Row;
import customdb.chapter08.DB.Storage;
import customdb.chapter08.Parser.Statement;
import java.io.IOException;
import java.util.Iterator;

public class DeleteOperator implements Operator {
  private final Storage storage;
  private final Statement.Delete statement;
  private Iterator<Storage.Record> iterator;

  public DeleteOperator(Storage storage, Statement.Delete statement) {
    this.storage = storage;
    this.statement = statement;
  }

  @Override
  public void open() throws IOException {
    this.iterator = storage.scanRecords().iterator();
  }

  @Override
  public Row next() throws IOException {
    while (iterator != null && iterator.hasNext()) {
      Storage.Record record = iterator.next();
      Row row = record.row();

      if (ConditionEvaluator.matches(row, statement.whereCondition())) {
        storage.delete(record.recordId());
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
