package customdb.minidbchap6.plan;

import customdb.minidbchap6.executor.ExecutionContext;
import customdb.minidbchap6.parser.Statement;
import java.io.IOException;

// 実行計画のインターフェース
public interface Plan {
  void execute(ExecutionContext context, Statement.Select statement) throws IOException;
}
