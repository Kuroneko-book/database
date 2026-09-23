package customdb.minidb.operator;

import customdb.minidb.db.Row;
import java.io.IOException;

public interface Operator extends AutoCloseable {
  void open() throws IOException;

  Row next() throws IOException;

  @Override
  void close() throws IOException;
}
