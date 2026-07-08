package customdb.chapter07.Operator;

import customdb.chapter07.DB.Row;
import customdb.chapter07.Parser.Statement.Condition;

public class ConditionEvaluator {
  public static boolean matches(Row row, Condition condition) {
    if (condition == null) return true;
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

  private static Object resolveColumn(Row row, String columnName) {
    // 列名は大文字小文字を無視して照合する（schema.getColumn と挙動を揃える）
    for (String key : row.keySet()) {
      if (key.equalsIgnoreCase(columnName)) return row.get(key);
    }
    String suffix = "." + columnName;
    Object found = null;
    int count = 0;
    for (String key : row.keySet()) {
      if (endsWithIgnoreCase(key, suffix)) {
        found = row.get(key);
        count++;
      }
    }
    if (count == 1) return found;
    if (count > 1) throw new IllegalArgumentException("Ambiguous column name: " + columnName);
    throw new IllegalArgumentException("Unknown column: " + columnName);
  }

  private static boolean endsWithIgnoreCase(String value, String suffix) {
    int offset = value.length() - suffix.length();
    return offset >= 0 && value.regionMatches(true, offset, suffix, 0, suffix.length());
  }

  private static Object resolveColumnOrLiteral(Row row, String value) {
    try {
      return resolveColumn(row, value);
    } catch (IllegalArgumentException ignored) {
    }
    String raw = stripQuote(value);
    if (isInteger(raw)) return Integer.parseInt(raw);
    if (isDouble(raw)) return Double.parseDouble(raw);
    return raw;
  }

  private static int compare(Object left, Object right) {
    if (left instanceof Number && right instanceof Number) {
      double l = toDouble(left);
      double r = toDouble(right);
      return Double.compare(l, r);
    }
    return left.toString().compareTo(right.toString());
  }

  private static double toDouble(Object value) {
    if (value instanceof Number number) return number.doubleValue();
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

  private static String stripQuote(String value) {
    if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
