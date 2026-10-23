package customdb.minidbchap7.operator;

import customdb.minidbchap7.table.Row;
import java.io.IOException;

public interface Operator {
  void open() throws IOException;

  Row next() throws IOException;

  void close() throws IOException;
}
