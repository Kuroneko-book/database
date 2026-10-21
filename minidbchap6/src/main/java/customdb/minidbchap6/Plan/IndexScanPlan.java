package customdb.minidbchap6.Plan;

import customdb.minidbchap6.executor.ExecutionContext;
import customdb.minidbchap6.parser.Statement;
import customdb.minidbchap6.parser.Statement.Condition;
import java.io.IOException;

// インデックス検索を行う実行計画
public class IndexScanPlan implements Plan {

  private final String tableName;
  private final Condition condition;

  // 検索条件を設定するコンストラクタ
  public IndexScanPlan(String tableName, Condition condition) {
    this.tableName = tableName;
    this.condition = condition;
  }

  public String getTableName() {
    return tableName;
  }

  public Condition getCondition() {
    return condition;
  }

  @Override
  public void execute(ExecutionContext context, Statement.Select statement) throws IOException {
    System.out.println("IndexScan : " + getTableName());
    context.executeIndexScan(statement);
  }
}
