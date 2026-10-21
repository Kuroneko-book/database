package customdb.minidbchap7.Plan;

import customdb.minidbchap7.executor.ExecutionContext;
import customdb.minidbchap7.parser.Statement;
import java.io.IOException;

// 実行計画のインターフェース
public interface Plan {
  void execute(ExecutionContext context, Statement.Select statement) throws IOException;
}
