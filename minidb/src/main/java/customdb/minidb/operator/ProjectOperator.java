package customdb.minidb.operator;

import customdb.minidb.db.Row;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class ProjectOperator implements Operator {
  private final Operator child;
  private final Map<String, String> projection;

  public ProjectOperator(Operator child, Map<String, String> projection) {
    this.child = child;
    this.projection = new LinkedHashMap<>(projection);
  }

  @Override
  public void open() throws IOException {
    child.open();
  }

  @Override
  public Row next() throws IOException {
    Row row = child.next();
    if (row == null) return null;

    Row projected = new Row();
    for (Map.Entry<String, String> entry : projection.entrySet()) {
      projected.put(entry.getKey(), row.get(entry.getValue()));
    }

    return projected;
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
