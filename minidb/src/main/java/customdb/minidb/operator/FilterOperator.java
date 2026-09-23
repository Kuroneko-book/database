package customdb.minidb.operator;

import customdb.minidb.db.Row;
import java.io.IOException;

public class FilterOperator implements Operator {
  private final Operator child;
  private final ConditionEvaluator.BoundCondition condition;

  public FilterOperator(Operator child, ConditionEvaluator.BoundCondition condition) {
    this.child = child;
    this.condition = condition;
  }

  @Override
  public void open() throws IOException {
    child.open();
  }

  @Override
  public Row next() throws IOException {
    while (true) {
      Row row = child.next();
      if (row == null) return null;
      if (ConditionEvaluator.matches(row, condition)) return row;
    }
  }

  @Override
  public void close() throws IOException {
    child.close();
  }

  @Override
  public String toString() {
    return child.toString();
  }
}
