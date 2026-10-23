package customdb.minidbchap6.executor;

import customdb.minidbchap6.parser.Statement;
import java.io.IOException;

public interface ExecutionContext {
  void executeSeqScan(Statement.Select statement) throws IOException;

  void executeIndexScan(Statement.Select statement) throws IOException;
}
