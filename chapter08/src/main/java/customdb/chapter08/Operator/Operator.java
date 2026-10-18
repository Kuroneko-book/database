package customdb.chapter08.Operator;

import customdb.chapter08.DB.Row;
import java.io.IOException;

public interface Operator {
  void open() throws IOException;

  Row next() throws IOException;

  void close() throws IOException;
}
