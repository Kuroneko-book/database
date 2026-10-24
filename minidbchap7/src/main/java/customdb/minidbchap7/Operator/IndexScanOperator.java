package customdb.minidbchap7.operator;

import customdb.minidbchap7.parser.Statement.Condition;
import customdb.minidbchap7.table.Row;
import customdb.minidbchap7.table.Schema;
import customdb.minidbchap7.table.Table;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class IndexScanOperator implements Operator {
  private final Table table;
  private final Schema schema;
  private final String tableName;
  private final Condition condition;
  private Iterator<Row> iterator;

  public IndexScanOperator(Table table, Schema schema, String tableName, Condition condition) {
    this.table = table;
    this.schema = schema;
    this.tableName = tableName;
    this.condition = condition;
  }

  @Override
  public void open() throws IOException {
    Schema.Column column = schema.getColumn(condition.left());
    if (column == null) {
      throw new IllegalArgumentException("Unknown column: " + condition.left());
    }

    String columnName = column.name();
    Object value = ValueParser.parseValue(condition.right(), column);

    List<Row> rows;
    if (condition.operator().equals("=")) {
      rows = table.searchByIndex(columnName, value);
    } else {
      // 範囲索引はint主キーのみが対象（ここでvaluwはintであることが保証）
      rows = table.searchRangeByIndex(columnName, (Integer) value, condition.operator());
    }
    this.iterator = rows.iterator();
  }

  @Override
  public Row next() throws IOException {
    if (iterator != null && iterator.hasNext()) {
      Row rawRow = iterator.next();
      return qualifyRow(tableName, schema, rawRow);
    }
    return null;
  }

  @Override
  public void close() throws IOException {
    this.iterator = null;
  }

  // 既存の行にrawカラムと表名付き列（id と users.id）の両方を詰めて返す（結合時に列名の衝突を避ける）
  private Row qualifyRow(String tableName, Schema schema, Row row) {
    Row qualified = new Row();
    for (Schema.Column column : schema.getColumns()) {
      String columnName = column.name();
      Object value = row.get(columnName);
      qualified.put(columnName, value);
      qualified.put(normalize(tableName) + "." + columnName, value);
    }
    return qualified;
  }

  // 表名・列名は大文字小文字を区別しない
  private String normalize(String name) {
    return name.toLowerCase(Locale.ROOT);
  }
}
