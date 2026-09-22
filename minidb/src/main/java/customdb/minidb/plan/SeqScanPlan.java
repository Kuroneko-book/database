package customdb.minidb.plan;

import customdb.minidb.db.Row;
import customdb.minidb.db.Table;
import java.io.IOException;
import java.util.List;

public record SeqScanPlan() implements Plan {
  @Override
  public List<Row> execute(Table table) throws IOException {
    return table.scan();
  }

  @Override
  public String toString() {
    return "SeqScan";
  }
}
