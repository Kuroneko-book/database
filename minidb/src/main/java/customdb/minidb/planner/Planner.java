package customdb.minidb.planner;

import customdb.minidb.db.Schema;
import customdb.minidb.plan.IndexScanPlan;
import customdb.minidb.plan.Plan;
import customdb.minidb.plan.SeqScanPlan;
import java.math.BigDecimal;

public class Planner {
  public Plan createPlan(Schema schema, String columnName, String operator, Object literal) {
    if (columnName == null || !"=".equals(operator) || !(literal instanceof BigDecimal number)) {
      return new SeqScanPlan();
    }

    String prefix = schema.getTableName() + ".";
    if (!columnName.startsWith(prefix)) {
      return new SeqScanPlan();
    }

    Schema.Column column = schema.getColumn(columnName.substring(prefix.length()));
    if (column == null || !column.indexed() || column.type() != Schema.DataType.INTEGER) {
      return new SeqScanPlan();
    }

    try {
      return new IndexScanPlan(column.name(), number.intValueExact());
    } catch (ArithmeticException e) {
      return new SeqScanPlan();
    }
  }
}
