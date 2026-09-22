package customdb.minidb.plan;

import customdb.minidb.db.Row;
import customdb.minidb.db.Table;
import java.util.List;

public record IndexScanPlan(String columnName, int value) implements Plan {
  @Override
  public List<Row> execute(Table table) {
    return table.searchByIndex(columnName, value);
  }

  @Override
  public String toString() {
    return "IndexScan";
  }
}
