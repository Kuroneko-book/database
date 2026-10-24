package customdb.minidbchap7.planner;

import customdb.minidbchap7.operator.DeleteOperator;
import customdb.minidbchap7.operator.FilterOperator;
import customdb.minidbchap7.operator.IndexScanOperator;
import customdb.minidbchap7.operator.InsertOperator;
import customdb.minidbchap7.operator.NestedLoopJoinOperator;
import customdb.minidbchap7.operator.Operator;
import customdb.minidbchap7.operator.ProjectOperator;
import customdb.minidbchap7.operator.SeqScanOperator;
import customdb.minidbchap7.operator.UpdateOperator;
import customdb.minidbchap7.operator.ValueParser;
import customdb.minidbchap7.parser.Statement;
import customdb.minidbchap7.table.Catalog;
import customdb.minidbchap7.table.Schema;
import customdb.minidbchap7.table.Table;
import java.util.Set;

public class Planner {
  private final Catalog catalog;

  public Planner(Catalog catalog) {
    this.catalog = catalog;
  }

  public Operator createPlan(Statement statement) {
    if (statement instanceof Statement.Select select) {
      return createSelectPlan(select);
    }
    if (statement instanceof Statement.Insert insert) {
      return new InsertOperator(
          catalog.requireTable(insert.tableName()),
          catalog.requireSchema(insert.tableName()),
          insert);
    }
    if (statement instanceof Statement.Update update) {
      return new UpdateOperator(
          catalog.requireTable(update.tableName()),
          catalog.requireSchema(update.tableName()),
          update);
    }
    if (statement instanceof Statement.Delete delete) {
      return new DeleteOperator(catalog.requireTable(delete.tableName()), delete);
    }
    throw new IllegalArgumentException("Unsupported statement for planning.");
  }

  private Operator createSelectPlan(Statement.Select statement) {
    Table leftTable = catalog.requireTable(statement.tableName());
    Schema leftSchema = catalog.requireSchema(statement.tableName());
    Statement.Condition condition = statement.whereCondition();

    Statement.JoinClause join = statement.joinClause();
    Schema rightSchema = null;
    Table rightTable = null;
    if (join != null) {
      rightSchema = catalog.requireSchema(join.tableName());
      rightTable = catalog.requireTable(join.tableName());
    }

    // WHEREがある場合にIndexScan可能か調べる
    boolean indexed = false;
    if (condition != null) {
      indexed = isIndexable(leftSchema, statement.tableName(), rightSchema, join, condition);
    }
    Operator plan;
    if (indexed) {
      System.out.println("IndexScan : " + statement.tableName());
      plan = new IndexScanOperator(leftTable, leftSchema, statement.tableName(), condition);
    } else {
      System.out.println("SeqScan : " + statement.tableName());
      plan = new SeqScanOperator(leftTable, leftSchema, statement.tableName());
    }

    if (join == null) {
      if (condition != null) {
        plan = new FilterOperator(plan, condition);
      }
      return new ProjectOperator(plan, statement.selectColumns(), false);
    }

    // 左表だけで絞れるWHEREはJOINの前に適用
    boolean pushDown = false;
    if (condition != null) {
      pushDown =
          canPushDownToLeft(
              condition, leftSchema, statement.tableName(), rightSchema, join.tableName());
    }
    if (pushDown) {
      plan = new FilterOperator(plan, condition);
    }

    Operator rightScan = new SeqScanOperator(rightTable, rightSchema, join.tableName());
    plan = new NestedLoopJoinOperator(plan, rightScan, join.onCondition());
    if (condition != null && !pushDown) {
      plan = new FilterOperator(plan, condition);
    }
    return new ProjectOperator(plan, statement.selectColumns(), true);
  }

  private boolean isIndexable(
      Schema leftSchema,
      String leftName,
      Schema rightSchema,
      Statement.JoinClause join,
      Statement.Condition condition) {
    Schema.Column column = leftSchema.getColumn(condition.left());
    // 左辺がIndexを張った左表の列でない場合と右辺が左表のカラムである場合は不可
    if (column == null
        || !column.isIndexed()
        || isColumnOf(condition.right(), leftSchema, leftName)) {
      return false;
    }
    // JOIN の右表の列を参照する条件で、左表だけを先に絞ってはいけない。
    if (join != null
        && (isColumnOf(condition.left(), rightSchema, join.tableName())
            || isColumnOf(condition.right(), rightSchema, join.tableName()))) {
      return false;
    }
    // 数値として読めない右辺を INT 索引に渡さない。文字列索引では先頭ゼロを保持する。
    if (column.type() == Schema.DataType.INTEGER && !isIntegerLiteral(condition.right())) {
      return false;
    }
    if (column.type() == Schema.DataType.STRING
        && !condition.right().startsWith("'")
        && isNumericLiteral(condition.right())) {
      return false;
    }
    if (column.type() != Schema.DataType.INTEGER
        && !isCompatibleLiteral(column, condition.right())) {
      return false;
    }
    return condition.operator().equals("=")
        || (column.type() == Schema.DataType.INTEGER
            && Set.of(">", ">=", "<", "<=").contains(condition.operator()));
  }

  private boolean canPushDownToLeft(
      Statement.Condition condition,
      Schema leftSchema,
      String leftName,
      Schema rightSchema,
      String rightName) {
    return isColumnOf(condition.left(), leftSchema, leftName)
        && !isColumnOf(condition.left(), rightSchema, rightName)
        && !isColumnOf(condition.right(), rightSchema, rightName);
  }

  private boolean isColumnOf(String token, Schema schema, String tableName) {
    if (schema == null) {
      return false;
    }
    int dot = token.indexOf('.');
    if (dot >= 0) {
      String qualifier = token.substring(0, dot);
      String columnName = token.substring(dot + 1);
      return tableName != null
          && qualifier.equalsIgnoreCase(tableName)
          && schema.getColumn(columnName) != null;
    }
    return schema.getColumn(token) != null;
  }

  private boolean isIntegerLiteral(String value) {
    if (value.startsWith("'")) {
      return false;
    }
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

  // 右辺リテラルを列の型へ変換できるかの判定　変換できない場合は SeqScan となる
  private boolean isCompatibleLiteral(Schema.Column column, String value) {
    try {
      ValueParser.parseValue(value, column);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }
}
