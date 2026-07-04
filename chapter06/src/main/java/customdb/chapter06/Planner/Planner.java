
// AST を実行計画へ変換する
public class Planner {

    public Plan createPlan(SelectStatement statement) {

        Condition condition = statement.getCondition();

        // WHERE句がない
        if (condition == null) {
            return new SeqScanPlan(statement.getTableName(), null);
        }

        // idならインデックスを利用
        if ("id".equals(condition.getColumn())) {
            return new IndexScanPlan(statement.getTableName(), condition);
        }

        // それ以外は全件検索
        return new SeqScanPlan(statement.getTableName(), condition);
    }

}