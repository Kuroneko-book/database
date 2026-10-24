package customdb.minidbchap7.operator;

import customdb.minidbchap7.table.Schema;
import java.nio.charset.StandardCharsets;

public final class ValueParser {
  private ValueParser() {}

  // Columnを用いて適切な型に変換する
  public static Object parseValue(String rawValue, Schema.Column column) {
    String value = stripQuote(rawValue);

    return switch (column.type()) {
      case INTEGER -> Integer.parseInt(value);
      case FLOAT -> Float.parseFloat(value);
      case DOUBLE -> Double.parseDouble(value);
      case STRING -> {
        // 格納はUTF-8バイト列なので、char数ではなくバイト長で上限を判定する
        if (column.length() > 0
            && value.getBytes(StandardCharsets.UTF_8).length > column.length()) {
          throw new IllegalArgumentException(
              "Value length exceeds column length. column="
                  + column.name()
                  + ", length="
                  + column.length()
                  + ", value="
                  + value);
        }
        yield value;
      }
    };
  }

  // Insertで値が指定されなかった列のデフォルト値
  public static Object defaultValue(Schema.Column column) {
    return switch (column.type()) {
      case INTEGER -> 0;
      case FLOAT -> 0.0f;
      case DOUBLE -> 0.0d;
      case STRING -> "";
    };
  }

  // クォートを削除
  static String stripQuote(String value) {
    if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
      return value.substring(1, value.length() - 1);
    }

    return value;
  }
}
