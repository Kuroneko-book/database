package customdb.minidbchap7.Planner;

import customdb.minidbchap7.Plan.IndexScanPlan;
import customdb.minidbchap7.Plan.Plan;
import customdb.minidbchap7.Plan.SeqScanPlan;
import customdb.minidbchap7.parser.Statement;
import customdb.minidbchap7.table.Schema;
import java.util.Set;

// AST を実行計画へ変換する
public class Planner {

  private static final Set<String> RANGE_OPERATORS = Set.of(">", ">=", "<", "<=");

  public Plan createPlan(Statement.Select statement, Schema schema) {
    Statement.Condition condition = statement.whereCondition();

    // WHERE句がない
    if (condition == null) {
      return new SeqScanPlan(statement.tableName(), null);
    }

    Schema.Column column = schema.getColumn(condition.left());

    /*
     以下の条件を全て満たす場合にIndexScanとなる
     - カラムにIndexが張られている
     - WHEREの左辺がカラム
     - 右辺がリテラル
     - 左辺がINTEGERなら、右辺が整数リテラル
     - 左辺がSTRINGなら、右辺は文字列
     - 演算子が=、または左辺がINTEGERで演算子が>、>=、<、<=のいずれか
    */
    if (column != null
        && column.isIndexed()
        && schema.getColumn(condition.right()) == null
        && (column.type() != Schema.DataType.INTEGER || isIntegerLiteral(condition.right()))
        && (column.type() != Schema.DataType.STRING
            || condition.right().startsWith("'")
            || !isNumericLiteral(condition.right()))
        && (condition.operator().equals("=")
            || (column.type() == Schema.DataType.INTEGER
                && RANGE_OPERATORS.contains(condition.operator())))) {
      return new IndexScanPlan(statement.tableName(), condition);
    }

    // それ以外は全件検索
    return new SeqScanPlan(statement.tableName(), condition);
  }

  private boolean isIntegerLiteral(String value) {
    if (value.startsWith("'")) return false;
    try {
      Integer.parseInt(value);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private boolean isNumericLiteral(String value) {
    try {
      Double.parseDouble(value);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
