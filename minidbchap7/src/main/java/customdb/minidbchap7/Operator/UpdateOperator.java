package customdb.minidbchap7.operator;

import customdb.minidbchap7.parser.Statement;
import customdb.minidbchap7.table.Row;
import customdb.minidbchap7.table.Schema;
import customdb.minidbchap7.table.Table;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class UpdateOperator implements Operator {
  private final Table table;
  private final Schema schema;
  private final Statement.Update statement;
  private List<Row> updatedRows;
  private Iterator<Row> iterator;

  public UpdateOperator(Table table, Schema schema, Statement.Update statement) {
    this.table = table;
    this.schema = schema;
    this.statement = statement;
  }

  @Override
  public void open() throws IOException {
    Schema.Column targetColumn = schema.getColumn(statement.columnName());
    if (targetColumn == null) {
      throw new IllegalArgumentException("Unknown column: " + statement.columnName());
    }

    Object newValue = ValueParser.parseValue(statement.value(), targetColumn);

    List<Table.Record> matched = new ArrayList<>();
    for (Table.Record record : table.scanRecords()) {
      if (ConditionEvaluator.matches(record.row(), statement.whereCondition())) {
        matched.add(record);
      }
    }

    // UPDATEで複数行の主キーに同じ値を設定する場合、主キーが重複するため書き込み前に拒否する
    if (targetColumn.primaryKey() && matched.size() > 1) {
      throw new IllegalArgumentException("Duplicate primary key: " + newValue);
    }

    updatedRows = new ArrayList<>();
    for (Table.Record record : matched) {
      Row updated = new Row();
      updated.putAll(record.row());
      updated.put(targetColumn.name(), newValue);
      table.update(record.recordId(), updated);
      updatedRows.add(updated);
    }

    iterator = updatedRows.iterator();
  }

  @Override
  public Row next() throws IOException {
    if (iterator != null && iterator.hasNext()) {
      return iterator.next();
    }
    return null;
  }

  @Override
  public void close() throws IOException {
    iterator = null;
  }
}
