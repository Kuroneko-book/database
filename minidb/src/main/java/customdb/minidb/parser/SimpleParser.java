package customdb.minidb.parser;

import customdb.minidb.db.Schema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SimpleParser {
  private final Tokenizer tokenizer;

  public SimpleParser() {
    tokenizer = new Tokenizer();
  }

  public Statement parseStatement(String sql) {
    List<String> tokens = normalize(tokenizer.tokenize(sql));

    if (tokens.isEmpty()) {
      throw new IllegalArgumentException("SQL is empty.");
    }

    return switch (word(tokens.get(0))) {
      case "select" -> parseSelect(tokens);
      case "insert" -> parseInsert(tokens);
      case "update" -> parseUpdate(tokens);
      case "delete" -> parseDelete(tokens);
      case "create" -> parseCreateTable(tokens);
      default -> throw new IllegalArgumentException("Unsupported SQL command: " + tokens.get(0));
    };
  }

  private List<String> normalize(List<String> source) {
    List<String> tokens = new ArrayList<>(source);
    int semicolon = tokens.indexOf(";");
    if (semicolon >= 0) {
      if (semicolon != tokens.size() - 1 || tokens.lastIndexOf(";") != semicolon) {
        throw new IllegalArgumentException("Semicolon is only allowed at the end of a statement.");
      }
      tokens.remove(tokens.size() - 1);
    }
    return tokens;
  }

  private Statement.Select parseSelect(List<String> tokens) {
    int index = 1;
    List<String> columns = new ArrayList<>();

    if (index >= tokens.size()) {
      throw new IllegalArgumentException("SELECT columns are missing.");
    }
    if (tokens.get(index).equals("*")) {
      columns.add("*");
      index++;
      if (index < tokens.size() && !is(tokens.get(index), "from")) {
        throw new IllegalArgumentException("Wildcard SELECT cannot include other columns.");
      }
    }

    while (index < tokens.size() && !is(tokens.get(index), "from")) {
      if (tokens.get(index).equals(",") || columns.isEmpty() && tokens.get(index).equals(")")) {
        throw new IllegalArgumentException("Invalid SELECT column list.");
      }
      columns.add(column(tokens.get(index)));
      index++;
      if (index < tokens.size() && !is(tokens.get(index), "from")) {
        require(tokens, index, ",");
        index++;
        if (index >= tokens.size()
            || is(tokens.get(index), "from")
            || tokens.get(index).equals(",")) {
          throw new IllegalArgumentException("SELECT column is missing.");
        }
      }
    }

    if (columns.isEmpty()) {
      throw new IllegalArgumentException("SELECT columns are missing.");
    }
    require(tokens, index, "from");
    index++;

    String table = table(tokens, index++);
    Statement.JoinClause join = null;
    Statement.Condition where = null;

    if (index < tokens.size() && is(tokens.get(index), "join")) {
      index++;
      String joinTable = table(tokens, index++);
      require(tokens, index++, "on");
      ParseCondition parsed = condition(tokens, index);
      index = parsed.nextIndex;
      join = new Statement.JoinClause(joinTable, parsed.value);
    }

    if (index < tokens.size() && is(tokens.get(index), "where")) {
      index++;
      ParseCondition parsed = condition(tokens, index);
      index = parsed.nextIndex;
      where = parsed.value;
    }

    finish(tokens, index);
    return new Statement.Select(columns, table, where, join);
  }

  private Statement.Insert parseInsert(List<String> tokens) {
    require(tokens, 1, "into");
    String table = table(tokens, 2);
    int index = 3;
    List<String> columns = new ArrayList<>();
    Set<String> seen = new HashSet<>();

    if (index < tokens.size() && tokens.get(index).equals("(")) {
      index++;
      if (index >= tokens.size() || tokens.get(index).equals(")")) {
        throw new IllegalArgumentException("INSERT column list is empty.");
      }
      while (true) {
        String name = bareColumn(tokens.get(index++));
        if (!seen.add(name)) {
          throw new IllegalArgumentException("Duplicate INSERT column: " + name);
        }
        columns.add(name);
        if (index >= tokens.size()) {
          throw new IllegalArgumentException("INSERT column list is not closed.");
        }
        if (tokens.get(index).equals(")")) {
          index++;
          break;
        }
        require(tokens, index++, ",");
        if (index >= tokens.size()
            || tokens.get(index).equals(")")
            || tokens.get(index).equals(",")) {
          throw new IllegalArgumentException("INSERT column is missing.");
        }
      }
    }

    require(tokens, index++, "values");
    require(tokens, index++, "(");
    List<String> values = literals(tokens, index);
    index = valueEnd(tokens, index);

    finish(tokens, index);
    return new Statement.Insert(table, columns, values);
  }

  private Statement.Update parseUpdate(List<String> tokens) {
    String table = table(tokens, 1);

    require(tokens, 2, "set");
    String column = column(tokens, 3);
    require(tokens, 4, "=");
    String value = literal(tokens, 5);
    int index = 6;
    Statement.Condition where = null;

    if (index < tokens.size() && is(tokens.get(index), "where")) {
      ParseCondition parsed = condition(tokens, index + 1);
      index = parsed.nextIndex;
      where = parsed.value;
    }

    finish(tokens, index);
    return new Statement.Update(table, column, value, where);
  }

  private Statement.Delete parseDelete(List<String> tokens) {
    require(tokens, 1, "from");
    String table = table(tokens, 2);
    int index = 3;
    Statement.Condition where = null;

    if (index < tokens.size() && is(tokens.get(index), "where")) {
      ParseCondition parsed = condition(tokens, index + 1);
      index = parsed.nextIndex;
      where = parsed.value;
    }

    finish(tokens, index);
    return new Statement.Delete(table, where);
  }

  private Statement.CreateTable parseCreateTable(List<String> tokens) {
    require(tokens, 1, "table");
    String table = table(tokens, 2);

    require(tokens, 3, "(");
    int index = 4;
    List<Schema.Column> columns = new ArrayList<>();
    if (index >= tokens.size() || tokens.get(index).equals(")")) {
      throw new IllegalArgumentException("CREATE TABLE requires columns.");
    }

    while (true) {
      String name = bareColumn(token(tokens, index++));
      String typeName = word(token(tokens, index++));
      Schema.DataType type;
      int length = 0;

      if (typeName.equals("int") || typeName.equals("integer")) {
        type = Schema.DataType.INTEGER;
      } else if (typeName.equals("float")) {
        type = Schema.DataType.FLOAT;
      } else if (typeName.equals("double")) {
        type = Schema.DataType.DOUBLE;
      } else if (typeName.equals("string") || typeName.equals("varchar")) {
        type = Schema.DataType.STRING;
        require(tokens, index++, "(");
        String lengthToken = token(tokens, index++);
        if (!lengthToken.matches("[1-9][0-9]*")) {
          throw new IllegalArgumentException("String length must be positive: " + lengthToken);
        }
        try {
          length = Integer.parseInt(lengthToken);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("String length is too large: " + lengthToken, e);
        }
        require(tokens, index++, ")");
      } else {
        throw new IllegalArgumentException("Unsupported data type: " + typeName);
      }

      boolean primaryKey = false;
      boolean indexed = false;

      while (index < tokens.size()
          && (is(tokens.get(index), "primary") || is(tokens.get(index), "index"))) {
        if (is(tokens.get(index), "primary")) {
          if (primaryKey) {
            throw new IllegalArgumentException("PRIMARY KEY is repeated: " + name);
          }
          primaryKey = true;
          index++;
          require(tokens, index++, "key");
        } else {
          if (indexed) {
            throw new IllegalArgumentException("INDEX is repeated: " + name);
          }
          indexed = true;
          index++;
        }
      }

      columns.add(new Schema.Column(name, type, length, primaryKey, indexed));

      if (index >= tokens.size()) {
        throw new IllegalArgumentException("CREATE TABLE is not closed.");
      }
      if (tokens.get(index).equals(")")) {
        index++;
        break;
      }
      require(tokens, index++, ",");
      if (index >= tokens.size()
          || tokens.get(index).equals(")")
          || tokens.get(index).equals(",")) {
        throw new IllegalArgumentException("CREATE TABLE column is missing.");
      }
    }

    finish(tokens, index);
    new Schema(table, columns);
    return new Statement.CreateTable(table, columns);
  }

  private ParseCondition condition(List<String> tokens, int index) {
    String left = column(tokens, index++);

    if (index >= tokens.size() || !operator(tokens.get(index))) {
      throw new IllegalArgumentException("Condition operator is missing.");
    }
    String op = tokens.get(index++);
    String rightToken = token(tokens, index++);
    String right = validColumn(rightToken) ? column(rightToken) : literal(rightToken);

    return new ParseCondition(new Statement.Condition(left, op, right), index);
  }

  private List<String> literals(List<String> tokens, int index) {
    List<String> values = new ArrayList<>();

    if (index >= tokens.size() || tokens.get(index).equals(")")) {
      throw new IllegalArgumentException("Value list is empty.");
    }

    while (true) {
      values.add(literal(tokens.get(index++)));
      if (index >= tokens.size()) {
        throw new IllegalArgumentException("Value list is not closed.");
      }
      if (tokens.get(index).equals(")")) {
        return values;
      }
      require(tokens, index++, ",");
      if (index >= tokens.size()
          || tokens.get(index).equals(")")
          || tokens.get(index).equals(",")) {
        throw new IllegalArgumentException("Value is missing.");
      }
    }
  }

  private int valueEnd(List<String> tokens, int index) {
    while (index < tokens.size() && !tokens.get(index).equals(")")) {
      index++;
    }
    if (index >= tokens.size()) {
      throw new IllegalArgumentException("Value list is not closed.");
    }
    return index + 1;
  }

  private String table(List<String> tokens, int index) {
    String value = token(tokens, index);
    if (!value.matches("[A-Za-z_][A-Za-z0-9_]*") || reserved(value)) {
      throw new IllegalArgumentException("Invalid table name: " + value);
    }
    return word(value);
  }

  private String column(List<String> tokens, int index) {
    return column(token(tokens, index));
  }

  private String column(String value) {
    if (!validColumn(value)) {
      throw new IllegalArgumentException("Invalid column name: " + value);
    }
    return word(value);
  }

  private String bareColumn(String value) {
    if (!value.matches("[A-Za-z_][A-Za-z0-9_]*") || reserved(value)) {
      throw new IllegalArgumentException("Invalid column name: " + value);
    }
    return word(value);
  }

  private boolean validColumn(String value) {
    String part = "[A-Za-z_][A-Za-z0-9_]*";
    return value.matches(part) && !reserved(value)
        || value.matches(part + "\\." + part)
            && !reserved(value.substring(0, value.indexOf('.')))
            && !reserved(value.substring(value.indexOf('.') + 1));
  }

  private String literal(String value) {
    if (value.matches("'[\\s\\S]*'")
        || value.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")) {
      return value;
    }
    throw new IllegalArgumentException("Invalid literal: " + value);
  }

  private String literal(List<String> tokens, int index) {
    return literal(token(tokens, index));
  }

  private boolean operator(String value) {
    return value.equals("=")
        || value.equals("!=")
        || value.equals("<")
        || value.equals("<=")
        || value.equals(">")
        || value.equals(">=");
  }

  private void require(List<String> tokens, int index, String expected) {
    if (index >= tokens.size() || !is(tokens.get(index), expected)) {
      throw new IllegalArgumentException("Expected '" + expected + "'.");
    }
  }

  private String token(List<String> tokens, int index) {
    if (index >= tokens.size()) {
      throw new IllegalArgumentException("Unexpected end of SQL.");
    }
    return tokens.get(index);
  }

  private void finish(List<String> tokens, int index) {
    if (index != tokens.size()) {
      throw new IllegalArgumentException("Unexpected token: " + tokens.get(index));
    }
  }

  private boolean is(String actual, String expected) {
    return word(actual).equals(expected);
  }

  private String word(String value) {
    return value.toLowerCase(Locale.ROOT);
  }

  private boolean reserved(String value) {
    return Set.of(
            "select", "from", "where", "join", "on", "insert", "into", "values", "update", "set",
            "delete", "create", "table", "and", "or", "null", "primary", "key", "int", "integer",
            "float", "double", "string", "varchar")
        .contains(word(value));
  }

  private record ParseCondition(Statement.Condition value, int nextIndex) {}
}
