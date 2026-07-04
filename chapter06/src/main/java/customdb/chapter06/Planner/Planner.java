package customdb.chapter06.Planner;

import customdb.chapter06.Plan.IndexScanPlan;
import customdb.chapter06.Plan.Plan;
import customdb.chapter06.Plan.SeqScanPlan;
import customdb.chapter06.Parser.Statement;

// AST を実行計画へ変換する
public class Planner {

    public Plan createPlan(Statement.Select statement) {

        Statement.Condition condition = statement.whereCondition();

        // WHERE句がない
        if (condition == null) {
            return new SeqScanPlan(statement.tableName(), null);
        }

        // idならインデックスを利用
        if (condition.left().equals("id")) {
            return new IndexScanPlan(statement.tableName(), condition);
        }

        // それ以外は全件検索
        return new SeqScanPlan(statement.tableName(), condition);
    }

}