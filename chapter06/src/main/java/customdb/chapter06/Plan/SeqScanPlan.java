
// 全件検索を行う実行計画
public class SeqScanPlan implements Plan {

    private final String tableName;
    private final Condition condition;

    // 検索条件を設定するコンストラクタ
    public SeqScanPlan(String tableName,Condition condition) {
        this.tableName = tableName;
        this.condition = condition;
    }

    public String getTableName() {
        return tableName;
    }

    public Condition getCondition() {
        return condition;
    }
}