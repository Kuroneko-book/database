package customdb.minidb.planner;

import customdb.minidb.db.Catalog;
import customdb.minidb.db.Schema;
import customdb.minidb.db.Table;
import customdb.minidb.operator.ConditionEvaluator;
import customdb.minidb.operator.ConditionEvaluator.BoundCondition;
import customdb.minidb.operator.DeleteOperator;
import customdb.minidb.operator.FilterOperator;
import customdb.minidb.operator.IndexScanOperator;
import customdb.minidb.operator.InsertOperator;
import customdb.minidb.operator.NestedLoopJoinOperator;
import customdb.minidb.operator.Operator;
import customdb.minidb.operator.ProjectOperator;
import customdb.minidb.operator.SeqScanOperator;
import customdb.minidb.operator.UpdateOperator;
import customdb.minidb.parser.Statement;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
      return new DeleteOperator(
          catalog.requireTable(delete.tableName()),
          catalog.requireSchema(delete.tableName()),
          delete);
    }

    throw new IllegalArgumentException("Unsupported statement for planning.");
  }

  private Operator createSelectPlan(Statement.Select statement) {
    Schema leftSchema = catalog.requireSchema(statement.tableName());
    List<Schema> schemas = new ArrayList<>();
    schemas.add(leftSchema);

    boolean hasJoin = statement.joinClause() != null;
    if (hasJoin) {
      Schema rightSchema = catalog.requireSchema(statement.joinClause().tableName());
      if (leftSchema.getTableName().equals(rightSchema.getTableName())) {
        throw new IllegalArgumentException("Self joins require aliases, which are not supported.");
      }
      schemas.add(rightSchema);
    }

    BoundCondition where = ConditionEvaluator.bindCondition(schemas, statement.whereCondition());
    BoundCondition on =
        hasJoin
            ? ConditionEvaluator.bindCondition(schemas, statement.joinClause().onCondition())
            : null;

    Map<String, String> projection = new LinkedHashMap<>();
    if (statement.selectColumns().equals(List.of("*"))) {
      for (Schema schema : schemas) {
        for (Schema.Column column : schema.getColumns()) {
          String qualified = schema.getTableName() + "." + column.name();
          projection.put(hasJoin ? qualified : column.name(), qualified);
        }
      }
    } else {
      for (String column : statement.selectColumns()) {
        projection.put(column, ConditionEvaluator.resolveColumn(schemas, column).name());
      }
    }

    Operator plan = createScan(leftSchema, where);
    boolean filterBeforeJoin = where != null && onlyReferences(where, leftSchema);
    if (filterBeforeJoin) {
      plan = new FilterOperator(plan, where);
    }

    if (hasJoin) {
      Schema rightSchema = schemas.get(1);
      Operator right =
          new SeqScanOperator(catalog.requireTable(rightSchema.getTableName()), rightSchema);
      plan = new NestedLoopJoinOperator(plan, right, on);
    }

    if (where != null && !filterBeforeJoin) {
      plan = new FilterOperator(plan, where);
    }

    return new ProjectOperator(plan, projection);
  }

  private Operator createScan(Schema schema, BoundCondition condition) {
    Table table = catalog.requireTable(schema.getTableName());
    if (condition != null
        && condition.operator().equals("=")
        && condition.literal() instanceof BigDecimal number
        && condition.left().name().startsWith(schema.getTableName() + ".")
        && condition.left().column().indexed()
        && condition.left().column().type() == Schema.DataType.INTEGER) {
      try {
        return new IndexScanOperator(
            table, schema, condition.left().column().name(), number.intValueExact());
      } catch (ArithmeticException e) {
        return new SeqScanOperator(table, schema);
      }
    }

    return new SeqScanOperator(table, schema);
  }

  private boolean onlyReferences(BoundCondition condition, Schema schema) {
    String prefix = schema.getTableName() + ".";
    return condition.left().name().startsWith(prefix)
        && (condition.right() == null || condition.right().name().startsWith(prefix));
  }
}
