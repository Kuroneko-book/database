package customdb.minidbchap6.executor;

import customdb.minidbchap6.Plan.Plan;
import customdb.minidbchap6.Planner.Planner;
import customdb.minidbchap6.parser.Statement;
import customdb.minidbchap6.table.Catalog;
import customdb.minidbchap6.table.Row;
import customdb.minidbchap6.table.Schema;
import customdb.minidbchap6.table.Table;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class QueryExecutor implements AutoCloseable, ExecutionContext {
  private final Catalog catalog;
  private final Planner planner = new Planner();

  public QueryExecutor(Path baseDir) throws IOException {
    this.catalog = new Catalog(baseDir);
  }

  public void execute(Statement statement) throws IOException {
    if (statement instanceof Statement.CreateTable createTableStatement) {
      executeCreateTable(createTableStatement);
    } else if (statement instanceof Statement.Insert insertStatement) {
      executeInsert(insertStatement);
    } else if (statement instanceof Statement.Select selectStatement) {
      executeSelect(selectStatement);
    } else if (statement instanceof Statement.Update updateStatement) {
      executeUpdate(updateStatement);
    } else if (statement instanceof Statement.Delete deleteStatement) {
      executeDelete(deleteStatement);
    } else {
      throw new IllegalArgumentException(
          "Unsupported statement: " + statement.getClass().getName());
    }
  }

  private void executeCreateTable(Statement.CreateTable statement) throws IOException {
    // cretateTableのASTからSchemaを作成し、Catalogに登録する
    Schema schema = new Schema(statement.tableName(), statement.columns());
    catalog.createTable(schema);
    System.out.println("Table created: " + statement.tableName());
  }

  private void executeInsert(Statement.Insert statement) throws IOException {
    Schema schema = catalog.requireSchema(statement.tableName());
    Table table = catalog.requireTable(statement.tableName());

    // insertのASTからRowを作成
    Row row = buildRow(schema, statement);

    table.insert(row);

    System.out.println("Inserted into " + statement.tableName() + ": " + row);
  }

  private void executeSelect(Statement.Select statement) throws IOException {
    Schema schema = catalog.requireSchema(statement.tableName());
    Plan plan = planner.createPlan(statement, schema);
    plan.execute(this, statement);
  }

  @Override
  public void executeSeqScan(Statement.Select statement) throws IOException {
    Table leftTable = catalog.requireTable(statement.tableName());
    Schema leftSchema = catalog.requireSchema(statement.tableName());
    List<Row> rows = new ArrayList<>();
    boolean hasJoin = statement.joinClause() != null;

    for (Row row : leftTable.scan()) {
      if (hasJoin) {
        rows.add(qualifyRow(statement.tableName(), leftSchema, row));
      } else {
        rows.add(row);
      }
    }

    printSelectedRows(rows, statement);
  }

  @Override
  public void executeIndexScan(Statement.Select statement) throws IOException {
    Table table = catalog.requireTable(statement.tableName());
    Schema schema = catalog.requireSchema(statement.tableName());
    Statement.Condition condition = statement.whereCondition();
    Schema.Column column = schema.getColumn(condition.left());
    Object value = parseValue(condition.right(), column);

    List<Row> rows =
        condition.operator().equals("=")
            ? table.searchByIndex(column.name(), value)
            : table.searchRangeByIndex(column.name(), (Integer) value, condition.operator());

    if (statement.joinClause() != null) {
      List<Row> qualified = new ArrayList<>();
      for (Row row : rows) {
        qualified.add(qualifyRow(statement.tableName(), schema, row));
      }
      rows = qualified;
    }

    printSelectedRows(rows, statement);
  }

  private void printSelectedRows(List<Row> rows, Statement.Select statement) throws IOException {
    boolean hasJoin = statement.joinClause() != null;
    if (hasJoin) {
      rows = executeJoin(rows, statement.joinClause());
    }

    List<Row> filteredRows = new ArrayList<>();

    for (Row row : rows) {
      if (matches(row, statement.whereCondition())) {
        filteredRows.add(row);
      }
    }

    printRows(filteredRows, statement.selectColumns(), hasJoin);
  }

  private void executeUpdate(Statement.Update statement) throws IOException {
    Schema schema = catalog.requireSchema(statement.tableName());
    Table table = catalog.requireTable(statement.tableName());

    Schema.Column targetColumn = schema.getColumn(statement.columnName());

    if (targetColumn == null) {
      throw new IllegalArgumentException("Unknown column: " + statement.columnName());
    }

    Object newValue = parseValue(statement.value(), targetColumn);

    List<Table.Record> matched = new ArrayList<>();
    for (Table.Record record : table.scanRecords()) {
      if (matches(record.row(), statement.whereCondition())) {
        matched.add(record);
      }
    }

    // UPDATEである値を複数行の主キーに設定する場合、主キーが重複するためはじく
    if (targetColumn.primaryKey() && matched.size() > 1) {
      throw new IllegalArgumentException("Duplicate primary key: " + newValue);
    }

    for (Table.Record record : matched) {
      Row updated = new Row();
      updated.putAll(record.row());
      updated.put(targetColumn.name(), newValue);
      table.update(record.recordId(), updated);
    }

    System.out.println("Updated " + matched.size() + " row(s).");
  }

  private void executeDelete(Statement.Delete statement) throws IOException {
    Table table = catalog.requireTable(statement.tableName());

    int deletedCount = 0;

    for (Table.Record record : table.scanRecords()) {
      if (matches(record.row(), statement.whereCondition())) {
        table.delete(record.recordId());
        deletedCount++;
      }
    }

    System.out.println("Deleted " + deletedCount + " row(s).");
  }

  private List<Row> executeJoin(List<Row> leftRows, Statement.JoinClause joinClause)
      throws IOException {
    String rightTableName = joinClause.tableName();

    Table rightTable = catalog.requireTable(rightTableName);
    Schema rightSchema = catalog.requireSchema(rightTableName);

    List<Row> rightRows = new ArrayList<>();

    for (Row right : rightTable.scan()) {
      rightRows.add(qualifyRow(rightTableName, rightSchema, right));
    }

    List<Row> result = new ArrayList<>();

    for (Row left : leftRows) {
      for (Row right : rightRows) {
        Row joined = new Row();
        joined.putAll(left);
        joined.putAll(right);

        if (matches(joined, joinClause.onCondition())) {
          result.add(joined);
        }
      }
    }

    return result;
  }

  // Insert文からRowを作成
  private Row buildRow(Schema schema, Statement.Insert statement) {
    Row row = new Row();

    for (Schema.Column column : schema.getColumns()) {
      row.put(column.name(), defaultValue(column));
    }

    List<String> columnNames = statement.columnNames();
    List<String> values = statement.values();

    Schema.Column primaryKey = schema.primaryKeyColumn();
    if (!columnNames.isEmpty()) {
      boolean supplied = false;
      for (String columnName : columnNames) {
        if (columnName.equalsIgnoreCase(primaryKey.name())) {
          supplied = true;
          break;
        }
      }
      if (!supplied) {
        throw new IllegalArgumentException(
            "Primary key column must be specified: " + primaryKey.name());
      }
    }

    if (columnNames.isEmpty()) {
      if (values.size() != schema.getColumns().size()) {
        throw new IllegalArgumentException(
            "Column count does not match value count. columns="
                + schema.getColumns().size()
                + ", values="
                + values.size());
      }

      for (int i = 0; i < schema.getColumns().size(); i++) {
        Schema.Column column = schema.getColumns().get(i);
        row.put(column.name(), parseValue(values.get(i), column));
      }

      return row;
    }

    if (columnNames.size() != values.size()) {
      throw new IllegalArgumentException(
          "Column count does not match value count. columns="
              + columnNames.size()
              + ", values="
              + values.size());
    }

    for (int i = 0; i < columnNames.size(); i++) {
      String columnName = columnNames.get(i);
      Schema.Column column = schema.getColumn(columnName);

      if (column == null) {
        throw new IllegalArgumentException("Unknown column: " + columnName);
      }

      row.put(column.name(), parseValue(values.get(i), column));
    }

    return row;
  }

  // Columnを用いて適切な型に変換する
  private Object parseValue(String rawValue, Schema.Column column) {
    String value = stripQuote(rawValue);

    return switch (column.type()) {
      case INTEGER -> Integer.parseInt(value);
      case FLOAT -> Float.parseFloat(value);
      case DOUBLE -> Double.parseDouble(value);
      case STRING -> {
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
  private Object defaultValue(Schema.Column column) {
    return switch (column.type()) {
      case INTEGER -> 0;
      case FLOAT -> 0.0f;
      case DOUBLE -> 0.0d;
      case STRING -> "";
    };
  }

  // 既存の行にテーブル名を付与して、結合時に列名の衝突を避ける(id -> users.id)
  private Row qualifyRow(String tableName, Schema schema, Row row) {
    Row qualified = new Row();

    for (Schema.Column column : schema.getColumns()) {
      String columnName = column.name();
      qualified.put(normalize(tableName) + "." + columnName, row.get(columnName));
    }

    return qualified;
  }

  // 表名・列名は大文字小文字を区別しない
  private String normalize(String name) {
    return name.toLowerCase(Locale.ROOT);
  }

  // 結果の行を出力する
  private void printRows(List<Row> rows, List<String> selectColumns, boolean hasJoin) {
    if (rows.isEmpty()) {
      System.out.println("(empty)");
      return;
    }

    for (Row row : rows) {
      Row projected = projectRow(row, selectColumns, hasJoin);
      System.out.println(projected);
    }
  }

  // SELECT句で指定された列のみ抽出する
  private Row projectRow(Row row, List<String> selectColumns, boolean hasJoin) {
    Row projected = new Row();

    if (selectColumns.size() == 1 && selectColumns.get(0).equals("*")) {
      for (String key : row.keySet()) {
        if (hasJoin) {
          // JOINではテーブル名付きの列だけを出力する
          if (key.contains(".")) {
            projected.put(key, row.get(key));
          }
        } else if (!key.contains(".")) {
          projected.put(key, row.get(key));
        }
      }

      return projected;
    }

    for (String columnName : selectColumns) {
      Object value = resolveColumn(row, columnName);
      projected.put(columnName, value);
    }

    return projected;
  }

  // RowがWHERE/ON条件に一致するかどうかを判定する
  public boolean matches(Row row, Statement.Condition condition) {
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

  private Object resolveColumn(Row row, String columnName) {
    // 完全一致
    if (row.contains(columnName)) {
      return row.get(columnName);
    }

    // 表名の大文字小文字を変えて指定しても、JOIN後の修飾列を解決できるようにする
    if (columnName.contains(".")) {
      for (String key : row.keySet()) {
        if (key.equalsIgnoreCase(columnName)) {
          return row.get(key);
        }
      }
    }

    // 修飾名（users.id）付きの列を探す
    String suffix = "." + columnName;
    Object found = null;
    int count = 0;

    for (String key : row.keySet()) {
      if (key.endsWith(suffix)) {
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

    throw new IllegalArgumentException("Unknown column: " + columnName);
  }

  // 右辺の値を、リテラル値またはカラム名として解決する（where age >= 20 or join on users.id = orders.user_id）
  private Object resolveColumnOrLiteral(Row row, String value) {
    // クォートで囲まれた値は文字列として扱う
    if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
      return stripQuote(value);
    }
    try {
      return resolveColumn(row, value);
    } catch (IllegalArgumentException ignored) {
      // カラム名ではなくリテラル値として扱う
    }

    String raw = stripQuote(value);

    if (isInteger(raw)) {
      return Integer.parseInt(raw);
    }

    if (isDouble(raw)) {
      return Double.parseDouble(raw);
    }

    return raw;
  }

  private int compare(Object left, Object right) {
    if (left instanceof Number && right instanceof Number) {
      return Double.compare(toDouble(left), toDouble(right));
    }
    // 左辺が数値かつ右辺がクォートで囲まれた数値の場合は、右辺を文字列ではなく数値として解釈して比較する
    if (left instanceof Number && right instanceof String text && isDouble(text)) {
      return Double.compare(toDouble(left), Double.parseDouble(text));
    }

    return left.toString().compareTo(right.toString());
  }

  private double toDouble(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }

    return Double.parseDouble(value.toString());
  }

  private boolean isInteger(String value) {
    try {
      Integer.parseInt(value);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private boolean isDouble(String value) {
    try {
      Double.parseDouble(value);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  // クォートを削除
  private String stripQuote(String value) {
    if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
      return value.substring(1, value.length() - 1);
    }

    return value;
  }

  @Override
  public void close() throws IOException {
    catalog.close();
  }
}
