package customdb.chapter08.Planner;

import customdb.chapter08.DB.Catalog;
import customdb.chapter08.DB.Schema;
import customdb.chapter08.DB.Table;
import customdb.chapter08.Operator.DeleteOperator;
import customdb.chapter08.Operator.FilterOperator;
import customdb.chapter08.Operator.IndexScanOperator;
import customdb.chapter08.Operator.InsertOperator;
import customdb.chapter08.Operator.NestedLoopJoinOperator;
import customdb.chapter08.Operator.Operator;
import customdb.chapter08.Operator.ProjectOperator;
import customdb.chapter08.Operator.SeqScanOperator;
import customdb.chapter08.Operator.UpdateOperator;
import customdb.chapter08.Parser.Statement;

public class Planner {
  private final Catalog catalog;

  public Planner(Catalog catalog) {
    this.catalog = catalog;
  }

  public Operator createPlan(Statement statement) {
    if (statement instanceof Statement.Select selectStmt) {
      return createSelectPlan(selectStmt);
    } else if (statement instanceof Statement.Insert insertStmt) {
      return createInsertPlan(insertStmt);
    } else if (statement instanceof Statement.Update updateStmt) {
      return createUpdatePlan(updateStmt);
    } else if (statement instanceof Statement.Delete deleteStmt) {
      return createDeletePlan(deleteStmt);
    } else {
      throw new IllegalArgumentException("Unsupported statement for planning.");
    }
  }

  private Operator createSelectPlan(Statement.Select statement) {
    Table table = catalog.requireTable(statement.tableName());
    Schema schema = catalog.requireSchema(statement.tableName());
    Statement.Condition condition = statement.whereCondition();

    Operator plan;

    if (condition != null) {
      Schema.Column column = schema.getColumn(condition.left());
      if (column != null
          && column.isIndexed()
          && column.type() == Schema.DataType.INTEGER
          && condition.operator().equals("=")) {
        plan = new IndexScanOperator(table, schema, statement.tableName(), condition);
      } else {
        plan = new SeqScanOperator(table, schema, statement.tableName());
      }
    } else {
      plan = new SeqScanOperator(table, schema, statement.tableName());
    }

    boolean hasJoin = statement.joinClause() != null;
    if (hasJoin) {
      String rightTableName = statement.joinClause().tableName();
      Table rightTable = catalog.requireTable(rightTableName);
      Schema rightSchema = catalog.requireSchema(rightTableName);
      Operator rightScan = new SeqScanOperator(rightTable, rightSchema, rightTableName);

      plan = new NestedLoopJoinOperator(plan, rightScan, statement.joinClause().onCondition());
    }

    if (condition != null) {
      plan = new FilterOperator(plan, condition);
    }

    plan = new ProjectOperator(plan, statement.selectColumns(), hasJoin);

    return plan;
  }

  private Operator createInsertPlan(Statement.Insert statement) {
    Table table = catalog.requireTable(statement.tableName());
    Schema schema = catalog.requireSchema(statement.tableName());
    return new InsertOperator(table, schema, statement);
  }

  private Operator createUpdatePlan(Statement.Update statement) {
    Table table = catalog.requireTable(statement.tableName());
    Schema schema = catalog.requireSchema(statement.tableName());
    return new UpdateOperator(table, schema, statement);
  }

  private Operator createDeletePlan(Statement.Delete statement) {
    Table table = catalog.requireTable(statement.tableName());
    return new DeleteOperator(table, statement);
  }
}
