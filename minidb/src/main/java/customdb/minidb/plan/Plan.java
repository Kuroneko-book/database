package customdb.minidb.plan;

import customdb.minidb.db.Row;
import customdb.minidb.db.Table;
import java.io.IOException;
import java.util.List;

public interface Plan {
  List<Row> execute(Table table) throws IOException;
}
