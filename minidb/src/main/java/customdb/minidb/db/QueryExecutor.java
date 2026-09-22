package customdb.minidb.db;

import customdb.minidb.parser.Statement;
import customdb.minidb.plan.Plan;
import customdb.minidb.planner.Planner;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class QueryExecutor implements AutoCloseable {
  private final Catalog catalog;
  private final Planner planner;

  public QueryExecutor(Path baseDir) throws IOException {
    this.catalog = new Catalog(baseDir);
    this.planner = new Planner();
  }

  public void execute(Statement statement) throws IOException {
    if (statement instanceof Statement.CreateTable create) {
      catalog.createTable(new Schema(create.tableName(), create.columns()));
      System.out.println("Table created: " + create.tableName());
    } else if (statement instanceof Statement.Insert insert) {
      executeInsert(insert);
    } else if (statement instanceof Statement.Select select) {
      executeSelect(select);
    } else if (statement instanceof Statement.Update update) {
      executeUpdate(update);
    } else if (statement instanceof Statement.Delete delete) {
      executeDelete(delete);
    } else {
      throw new IllegalArgumentException("Unsupported statement.");
    }
  }

  private void executeInsert(Statement.Insert statement) throws IOException {
    Schema schema = catalog.requireSchema(statement.tableName());
    Table table = catalog.requireTable(statement.tableName());

    Row row = buildRow(schema, statement);
    table.insert(row);

    System.out.println("Inserted into " + schema.getTableName() + ": " + row);
  }

  private Row buildRow(Schema schema, Statement.Insert statement) {
    List<String> names = statement.columnNames();
    List<String> values = statement.values();

    Row row = new Row();
    for (Schema.Column column : schema.getColumns()) {
      row.put(column.name(), schema.defaultValue(column));
    }

    if (names.isEmpty()) {
      if (values.size() != schema.getColumns().size()) {
        throw new IllegalArgumentException("Column count does not match value count.");
      }

      for (int i = 0; i < values.size(); i++) {
        Schema.Column column = schema.getColumns().get(i);
        row.put(column.name(), schema.parseValue(values.get(i), column));
      }
    } else {
      if (names.size() != values.size()) {
        throw new IllegalArgumentException("Column count does not match value count.");
      }

      Set<String> assigned = new HashSet<>();
      for (int i = 0; i < names.size(); i++) {
        Schema.Column column = schema.getColumn(names.get(i));
        if (column == null) throw new IllegalArgumentException("Unknown column: " + names.get(i));
        if (!assigned.add(column.name()))
          throw new IllegalArgumentException("Duplicate column: " + names.get(i));
        row.put(column.name(), schema.parseValue(values.get(i), column));
      }

      if (!assigned.contains(schema.getKeyColumn().name())) {
        throw new IllegalArgumentException(
            "Primary key must be supplied: " + schema.getKeyColumn().name());
      }
    }

    return row;
  }

  private void executeSelect(Statement.Select statement) throws IOException {
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

    BoundCondition where = bindCondition(schemas, statement.whereCondition());
    BoundCondition on =
        hasJoin ? bindCondition(schemas, statement.joinClause().onCondition()) : null;

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
        projection.put(column, resolveColumn(schemas, column).name());
      }
    }

    Plan plan =
        planner.createPlan(
            leftSchema,
            where == null ? null : where.left().name(),
            where == null ? null : where.operator(),
            where == null ? null : where.literal());
    System.out.println(plan + " : " + leftSchema.getTableName());
    List<Row> leftRows = plan.execute(catalog.requireTable(leftSchema.getTableName()));

    List<Row> rightRows = new ArrayList<>();
    if (hasJoin) {
      for (Row row : catalog.requireTable(schemas.get(1).getTableName()).scan()) {
        rightRows.add(qualifyRow(schemas.get(1), row));
      }
    }

    boolean found = false;
    for (Row raw : leftRows) {
      Row left = qualifyRow(leftSchema, raw);
      if (!hasJoin) {
        if (matches(left, where)) {
          printRow(left, projection);
          found = true;
        }
      } else {
        for (Row right : rightRows) {
          Row joined = new Row();
          joined.putAll(left);
          joined.putAll(right);
          if (matches(joined, on) && matches(joined, where)) {
            printRow(joined, projection);
            found = true;
          }
        }
      }
    }

    if (!found) System.out.println("(empty)");
  }

  private void executeUpdate(Statement.Update statement) throws IOException {
    Schema schema = catalog.requireSchema(statement.tableName());
    Table table = catalog.requireTable(statement.tableName());
    Schema.Column column = resolveColumn(List.of(schema), statement.columnName()).column();
    Object value = schema.parseValue(statement.value(), column);
    BoundCondition condition = bindCondition(List.of(schema), statement.whereCondition());

    Map<String, Row> updates = new LinkedHashMap<>();
    Set<String> keys = new HashSet<>();

    for (Table.Record record : table.scanRecords()) {
      if (!matches(qualifyRow(schema, record.row()), condition)) continue;

      Row row = record.row();
      row.put(column.name(), value);
      table.validate(row);

      String newKey = schema.key(row);
      if (!keys.add(newKey) || (!newKey.equals(record.key()) && table.containsKey(newKey))) {
        throw new IllegalArgumentException("Key already exists: " + newKey);
      }

      updates.put(record.key(), row);
    }

    for (Map.Entry<String, Row> update : updates.entrySet()) {
      table.update(update.getKey(), update.getValue());
    }

    System.out.println("Updated " + updates.size() + " row(s).");
  }

  private void executeDelete(Statement.Delete statement) throws IOException {
    Schema schema = catalog.requireSchema(statement.tableName());
    Table table = catalog.requireTable(statement.tableName());
    BoundCondition condition = bindCondition(List.of(schema), statement.whereCondition());

    int count = 0;

    for (Table.Record record : table.scanRecords()) {
      if (matches(qualifyRow(schema, record.row()), condition)) {
        table.delete(record.key());
        count++;
      }
    }

    System.out.println("Deleted " + count + " row(s).");
  }

  private Row qualifyRow(Schema schema, Row row) {
    Row qualified = new Row();
    for (Schema.Column column : schema.getColumns()) {
      qualified.put(schema.getTableName() + "." + column.name(), row.get(column.name()));
    }

    return qualified;
  }

  private void printRow(Row row, Map<String, String> projection) {
    Row projected = new Row();
    for (Map.Entry<String, String> column : projection.entrySet()) {
      projected.put(column.getKey(), row.get(column.getValue()));
    }

    System.out.println(projected);
  }

  private ColumnReference resolveColumn(List<Schema> schemas, String name) {
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

  private BoundCondition bindCondition(List<Schema> schemas, Statement.Condition condition) {
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

  private boolean matches(Row row, BoundCondition condition) {
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

  private record ColumnReference(String name, Schema.Column column) {}

  private record BoundCondition(
      ColumnReference left, String operator, ColumnReference right, Object literal) {}

  @Override
  public void close() throws IOException {
    catalog.close();
  }
}
