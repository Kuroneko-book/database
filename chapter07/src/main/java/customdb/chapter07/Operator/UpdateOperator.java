package customdb.chapter07.Operator;

import customdb.chapter07.DB.Row;
import customdb.chapter07.DB.Schema;
import customdb.chapter07.DB.Table;
import customdb.chapter07.Parser.Statement;
import java.io.IOException;
import java.util.Iterator;

public class UpdateOperator implements Operator {
  private final Table table;
  private final Schema schema;
  private final Statement.Update statement;
  private Iterator<Table.Record> iterator;
  private boolean mutated = false;

  public UpdateOperator(Table table, Schema schema, Statement.Update statement) {
    this.table = table;
    this.schema = schema;
    this.statement = statement;
  }

  @Override
  public void open() throws IOException {
    this.iterator = table.scanRecords().iterator();
  }

  @Override
  public Row next() throws IOException {
    Schema.Column targetColumn = schema.getColumn(statement.columnName());
    if (targetColumn == null) {
      throw new IllegalArgumentException("Unknown column: " + statement.columnName());
    }

    while (iterator != null && iterator.hasNext()) {
      Table.Record record = iterator.next();
      Row row = record.row();

      if (ConditionEvaluator.matches(row, statement.whereCondition())) {
        Object newValue = parseValue(statement.value(), targetColumn);
        row.put(targetColumn.name(), newValue);
        table.update(record.recordId(), row);
        mutated = true;
        return row;
      }
    }

    return null;
  }

  @Override
  public void close() throws IOException {
    // 書き換えが発生した場合はインデックスを最新の状態に作り直す
    if (mutated) {
      table.rebuildIndexes();
    }
    this.iterator = null;
  }

  private Object parseValue(String rawValue, Schema.Column column) {
    String value = stripQuote(rawValue);
    return switch (column.type()) {
      case INTEGER -> Integer.parseInt(value);
      case FLOAT -> Float.parseFloat(value);
      case DOUBLE -> Double.parseDouble(value);
      case STRING -> {
        if (value.length() > column.length()) {
          throw new IllegalArgumentException(
              "Value length exceeds column length. column="
                  + column.name()
                  + ", length="
                  + column.length()
                  + ", value="
                  + value);
        }
        yield value;
      }
    };
  }

  private String stripQuote(String value) {
    if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
