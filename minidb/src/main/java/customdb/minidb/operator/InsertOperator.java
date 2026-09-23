package customdb.minidb.operator;

import customdb.minidb.db.Row;
import customdb.minidb.db.Schema;
import customdb.minidb.db.Table;
import customdb.minidb.parser.Statement;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class InsertOperator implements Operator {
  private final Table table;
  private final Row row;
  private boolean opened;
  private boolean executed;

  public InsertOperator(Table table, Schema schema, Statement.Insert statement) {
    this.table = table;
    this.row = buildRow(schema, statement);
    table.validate(row);
  }

  @Override
  public void open() {
    opened = true;
    executed = false;
  }

  @Override
  public Row next() throws IOException {
    if (!opened || executed) return null;

    Row inserted = new Row();
    inserted.putAll(row);
    table.insert(inserted);
    executed = true;
    return inserted;
  }

  @Override
  public void close() {
    opened = false;
    executed = false;
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
}
