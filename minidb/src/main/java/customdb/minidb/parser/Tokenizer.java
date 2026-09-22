package customdb.minidb.parser;

import java.util.ArrayList;
import java.util.List;

public class Tokenizer {
  public List<String> tokenize(String sql) {
    List<String> tokens = new ArrayList<>();
    if (sql == null || sql.isBlank()) {
      return tokens;
    }

    StringBuilder current = new StringBuilder();

    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (Character.isWhitespace(c)) {
        flush(current, tokens);
      } else if (c == '\'') {
        flush(current, tokens);
        int end = i + 1;
        boolean closed = false;
        while (end < sql.length()) {
          if (sql.charAt(end) == '\'') {
            if (end + 1 < sql.length() && sql.charAt(end + 1) == '\'') {
              end += 2;
            } else {
              end++;
              tokens.add(sql.substring(i, end));
              i = end - 1;
              closed = true;
              break;
            }
          } else {
            end++;
          }
        }
        if (!closed) {
          throw new IllegalArgumentException("String literal is not closed.");
        }
      } else if (c == '"') {
        throw new IllegalArgumentException("Double-quoted identifiers are not supported.");
      } else if (c == '(' || c == ')' || c == ',' || c == ';' || c == '*') {
        flush(current, tokens);
        tokens.add(String.valueOf(c));
      } else if (c == '=' || c == '<' || c == '>') {
        flush(current, tokens);
        if (i + 1 < sql.length() && sql.charAt(i + 1) == '=') {
          tokens.add(sql.substring(i, i + 2));
          i++;
        } else {
          tokens.add(String.valueOf(c));
        }
      } else if (c == '!') {
        flush(current, tokens);
        if (i + 1 < sql.length() && sql.charAt(i + 1) == '=') {
          tokens.add("!=");
          i++;
        } else {
          throw new IllegalArgumentException("Unsupported operator: !");
        }
      } else {
        current.append(c);
      }
    }

    flush(current, tokens);
    return tokens;
  }

  private void flush(StringBuilder current, List<String> tokens) {
    if (current.length() > 0) {
      tokens.add(current.toString());
      current.setLength(0);
    }
  }
}
