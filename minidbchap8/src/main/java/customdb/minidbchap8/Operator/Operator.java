package customdb.minidbchap8.Operator;

import customdb.minidbchap8.DB.Row;
import java.io.IOException;

public interface Operator {
  void open() throws IOException;

  Row next() throws IOException;

  void close() throws IOException;
}
