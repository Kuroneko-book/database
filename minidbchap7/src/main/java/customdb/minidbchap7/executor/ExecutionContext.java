package customdb.minidbchap7.executor;

import customdb.minidbchap7.parser.Statement;
import java.io.IOException;

public interface ExecutionContext {
  void executeSeqScan(Statement.Select statement) throws IOException;

  void executeIndexScan(Statement.Select statement) throws IOException;
}
