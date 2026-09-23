package customdb.minidb.operator;

import customdb.minidb.db.Row;
import customdb.minidb.db.Schema;
import customdb.minidb.parser.Statement;
import java.math.BigDecimal;
import java.util.List;

public final class ConditionEvaluator {
  private ConditionEvaluator() {}

  public static Row qualifyRow(Schema schema, Row row) {
    Row qualified = new Row();
    for (Schema.Column column : schema.getColumns()) {
      qualified.put(schema.getTableName() + "." + column.name(), row.get(column.name()));
    }

    return qualified;
  }

  public static ColumnReference resolveColumn(List<Schema> schemas, String name) {
    ColumnReference found = null;
    String[] parts = name.split("\\.", -1);
    if (parts.length > 2) throw new IllegalArgumentException("Invalid column: " + name);

    for (Schema schema : schemas) {
      if (parts.length == 2 && !parts[0].equalsIgnoreCase(schema.getTableName())) continue;
      Schema.Column column = schema.getColumn(parts[parts.length - 1]);
      if (column == null) continue;
      if (found != null) throw new IllegalArgumentException("Ambiguous column: " + name);
      found = new ColumnReference(schema.getTableName() + "." + column.name(), column);
    }

    if (found == null) throw new IllegalArgumentException("Unknown column: " + name);

    return found;
  }

  public static BoundCondition bindCondition(List<Schema> schemas, Statement.Condition condition) {
    if (condition == null) return null;

    ColumnReference left = resolveColumn(schemas, condition.left());
    ColumnReference right = null;
    Object literal = null;
    String token = condition.right();
    if (token.startsWith("'") && token.endsWith("'")) {
      literal = token.substring(1, token.length() - 1).replace("''", "'");
    } else if (token.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
      literal = new BigDecimal(token);
    } else {
      right = resolveColumn(schemas, token);
    }

    boolean leftString = left.column().type() == Schema.DataType.STRING;
    boolean rightString =
        right == null ? literal instanceof String : right.column().type() == Schema.DataType.STRING;
    if (leftString != rightString) {
      throw new IllegalArgumentException("Cannot compare string and numeric values.");
    }
    if (!List.of("=", "!=", "<", "<=", ">", ">=").contains(condition.operator())) {
      throw new IllegalArgumentException("Unsupported operator: " + condition.operator());
    }

    return new BoundCondition(left, condition.operator(), right, literal);
  }

  public static boolean matches(Row row, BoundCondition condition) {
    if (condition == null) return true;

    Object left = row.get(condition.left().name());
    Object right =
        condition.right() == null ? condition.literal() : row.get(condition.right().name());

    int cmp;
    if (left instanceof Number && right instanceof Number) {
      cmp = new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
    } else {
      cmp = left.toString().compareTo(right.toString());
    }

    return switch (condition.operator()) {
      case "=" -> cmp == 0;
      case "!=" -> cmp != 0;
      case "<" -> cmp < 0;
      case "<=" -> cmp <= 0;
      case ">" -> cmp > 0;
      case ">=" -> cmp >= 0;
      default -> throw new IllegalArgumentException("Unsupported operator.");
    };
  }

  public record ColumnReference(String name, Schema.Column column) {}

  public record BoundCondition(
      ColumnReference left, String operator, ColumnReference right, Object literal) {}
}
