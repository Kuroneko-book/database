package customdb.minidbchap6.planner;

import customdb.minidbchap6.parser.Statement;
import customdb.minidbchap6.plan.IndexScanPlan;
import customdb.minidbchap6.plan.Plan;
import customdb.minidbchap6.plan.SeqScanPlan;
import customdb.minidbchap6.table.Schema;
import java.nio.charset.StandardCharsets;
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

    String right = condition.right();
    boolean quoted = right.startsWith("'");
    boolean qualifiedColumn =
        !quoted && right.matches("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*");
    boolean possibleJoinColumn =
        statement.joinClause() != null && !quoted && right.matches("[A-Za-z_][A-Za-z0-9_]*");

    // insert用の値変換が失敗する検索値ならIndexScan
    boolean compatibleLiteral = false;
    if (column != null) {
      compatibleLiteral = isCompatibleLiteral(column, right);
    }

    /*
     以下の条件を全て満たす場合にIndexScanとなる
     - カラムにIndexが張られている
     - WHEREの左辺がカラム
     - 右辺が修飾付き列名ではない
     - Jjoinにおいて、右辺が結合先の列名の列名である可能性がない
     - 右辺が検索対象列の値として扱える
     - 右辺がリテラル
     - 左辺がINTEGERなら、右辺が整数リテラル
     - 左辺がSTRINGなら、右辺は文字列
     - 演算子が=、または左辺がINTEGERで演算子が>、>=、<、<=のいずれか
    */
    if (column != null
        && column.isIndexed()
        && schema.getColumn(condition.right()) == null
        && !qualifiedColumn
        && !possibleJoinColumn
        && compatibleLiteral
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

  private boolean isCompatibleLiteral(Schema.Column column, String rawValue) {
    String value = rawValue;
    if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
      value = value.substring(1, value.length() - 1);
    }
    if (column.type() == Schema.DataType.STRING) {
      return column.length() <= 0
          || value.getBytes(StandardCharsets.UTF_8).length <= column.length();
    }
    if (column.type() == Schema.DataType.FLOAT || column.type() == Schema.DataType.DOUBLE) {
      try {
        if (column.type() == Schema.DataType.FLOAT) {
          Float.parseFloat(value);
        } else {
          Double.parseDouble(value);
        }
        return true;
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return true;
  }
}
