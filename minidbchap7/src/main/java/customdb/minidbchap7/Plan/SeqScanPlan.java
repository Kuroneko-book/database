package customdb.minidbchap7.Plan;

import customdb.minidbchap7.executor.ExecutionContext;
import customdb.minidbchap7.parser.Statement;
import customdb.minidbchap7.parser.Statement.Condition;
import java.io.IOException;

// 全件検索を行う実行計画
public class SeqScanPlan implements Plan {

  private final String tableName;
  private final Condition condition;

  // 検索条件を設定するコンストラクタ
  public SeqScanPlan(String tableName, Condition condition) {
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
    System.out.println("SeqScan : " + getTableName());
    context.executeSeqScan(statement);
  }
}
