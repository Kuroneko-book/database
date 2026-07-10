package customdb.chapter08.Planner;

import customdb.chapter08.DB.Catalog;
import customdb.chapter08.DB.Schema;
import customdb.chapter08.DB.Storage;
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
    Storage storage = catalog.requireStorage(statement.tableName());
    Schema schema = catalog.requireSchema(statement.tableName());
    Statement.Condition condition = statement.whereCondition();

    Operator plan;

    if (condition != null) {
      Schema.Column column = schema.getColumn(condition.left());
      if (column != null
          && column.isIndexed()
          && column.type() == Schema.DataType.INTEGER
          && condition.operator().equals("=")) {
        plan = new IndexScanOperator(storage, schema, statement.tableName(), condition);
      } else {
        plan = new SeqScanOperator(storage, schema, statement.tableName());
      }
    } else {
      plan = new SeqScanOperator(storage, schema, statement.tableName());
    }

    boolean hasJoin = statement.joinClause() != null;
    if (hasJoin) {
      String rightTableName = statement.joinClause().tableName();
      Storage rightStorage = catalog.requireStorage(rightTableName);
      Schema rightSchema = catalog.requireSchema(rightTableName);
      Operator rightScan = new SeqScanOperator(rightStorage, rightSchema, rightTableName);

      plan = new NestedLoopJoinOperator(plan, rightScan, statement.joinClause().onCondition());
    }

    if (condition != null) {
      plan = new FilterOperator(plan, condition);
    }

    plan = new ProjectOperator(plan, statement.selectColumns(), hasJoin);

    return plan;
  }

  private Operator createInsertPlan(Statement.Insert statement) {
    Storage storage = catalog.requireStorage(statement.tableName());
    Schema schema = catalog.requireSchema(statement.tableName());
    return new InsertOperator(storage, schema, statement);
  }

  private Operator createUpdatePlan(Statement.Update statement) {
    Storage storage = catalog.requireStorage(statement.tableName());
    Schema schema = catalog.requireSchema(statement.tableName());
    return new UpdateOperator(storage, schema, statement);
  }

  private Operator createDeletePlan(Statement.Delete statement) {
    Storage storage = catalog.requireStorage(statement.tableName());
    return new DeleteOperator(storage, statement);
  }
}
