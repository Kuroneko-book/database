package customdb.minidbchap7.operator;

import customdb.minidbchap7.parser.Statement.Condition;
import customdb.minidbchap7.table.Row;

public class ConditionEvaluator {
  public static boolean matches(Row row, Condition condition) {
    if (condition == null) {
      return true;
    }

    Object leftValue = resolveColumn(row, condition.left());
    Object rightValue = resolveColumnOrLiteral(row, condition.right());

    int cmp = compare(leftValue, rightValue);

    return switch (condition.operator()) {
      case "=" -> cmp == 0;
      case "!=" -> cmp != 0;
      case ">" -> cmp > 0;
      case ">=" -> cmp >= 0;
      case "<" -> cmp < 0;
      case "<=" -> cmp <= 0;
      default ->
          throw new IllegalArgumentException("Unsupported operator: " + condition.operator());
    };
  }

  public static Object resolveColumn(Row row, String columnName) {
    Object found = findColumn(row, columnName);
    if (found == null) {
      throw new IllegalArgumentException("Unknown column: " + columnName);
    }
    return found;
  }

  // 列が無ければnull、同名列が複数あれば例外を返す
  private static Object findColumn(Row row, String columnName) {
    for (String key : row.keySet()) {
      if (key.equalsIgnoreCase(columnName)) {
        return row.get(key);
      }
    }

    // 修飾名（users.id）付きの列を探す
    String suffix = "." + columnName;
    Object found = null;
    int count = 0;

    for (String key : row.keySet()) {
      if (key.length() >= suffix.length()
          && key.regionMatches(true, key.length() - suffix.length(), suffix, 0, suffix.length())) {
        found = row.get(key);
        count++;
      }
    }

    if (count == 1) {
      return found;
    }

    if (count > 1) {
      throw new IllegalArgumentException("Ambiguous column name: " + columnName);
    }

    return null;
  }

  // 右辺の値を、リテラルまたはカラム名として解釈する
  private static Object resolveColumnOrLiteral(Row row, String value) {
    // クォートで囲まれた値は文字列として扱う
    if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
      return ValueParser.stripQuote(value);
    }
    Object columnValue = findColumn(row, value);
    if (columnValue != null) {
      return columnValue;
    }

    String raw = ValueParser.stripQuote(value);

    if (isInteger(raw)) {
      return Integer.parseInt(raw);
    }

    if (isDouble(raw)) {
      return Double.parseDouble(raw);
    }

    return raw;
  }

  private static int compare(Object left, Object right) {
    if (left instanceof Float number && right instanceof Number other) {
      return Float.compare(number, other.floatValue());
    }
    if (left instanceof Float number && right instanceof String text && isDouble(text)) {
      return Float.compare(number, Float.parseFloat(text));
    }
    if (left instanceof Number && right instanceof Number) {
      return Double.compare(toDouble(left), toDouble(right));
    }
    // 左辺が数値かつ右辺がクォートで囲まれた数値の場合は、右辺を文字列ではなく数値として解釈して比較する
    if (left instanceof Number && right instanceof String text && isDouble(text)) {
      return Double.compare(toDouble(left), Double.parseDouble(text));
    }

    return left.toString().compareTo(right.toString());
  }

  private static double toDouble(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }

    return Double.parseDouble(value.toString());
  }

  private static boolean isInteger(String value) {
    try {
      Integer.parseInt(value);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static boolean isDouble(String value) {
    try {
      Double.parseDouble(value);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
