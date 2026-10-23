package customdb.minidbchap7.operator;

import customdb.minidbchap7.table.Row;
import java.io.IOException;
import java.util.List;

public class ProjectOperator implements Operator {
  private final Operator child;
  private final List<String> selectColumns;
  private final boolean hasJoin;

  public ProjectOperator(Operator child, List<String> selectColumns, boolean hasJoin) {
    this.child = child;
    this.selectColumns = selectColumns;
    this.hasJoin = hasJoin;
  }

  @Override
  public void open() throws IOException {
    child.open();
  }

  @Override
  public Row next() throws IOException {
    Row row = child.next();
    if (row == null) {
      return null;
    }
    return projectRow(row, selectColumns, hasJoin);
  }

  @Override
  public void close() throws IOException {
    child.close();
  }

  private Row projectRow(Row row, List<String> selectColumns, boolean hasJoin) {
    Row projected = new Row();

    if (selectColumns.size() == 1 && selectColumns.get(0).equals("*")) {
      for (String key : row.keySet()) {
        if (hasJoin) {
          if (key.contains(".")) {
            projected.put(key, row.get(key));
          }
        } else {
          if (!key.contains(".")) {
            projected.put(key, row.get(key));
          }
        }
      }
      return projected;
    }

    for (String columnName : selectColumns) {
      Object value = ConditionEvaluator.resolveColumn(row, columnName);
      projected.put(columnName, value);
    }
    return projected;
  }
}
