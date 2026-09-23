package customdb.minidb.operator;

import customdb.minidb.db.Row;
import customdb.minidb.db.Schema;
import customdb.minidb.db.Table;
import customdb.minidb.parser.Statement;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UpdateOperator implements Operator {
  private final Table table;
  private final Schema schema;
  private final Schema.Column targetColumn;
  private final Object newValue;
  private final ConditionEvaluator.BoundCondition condition;
  private Iterator<Map.Entry<String, Row>> iterator;

  public UpdateOperator(Table table, Schema schema, Statement.Update statement) {
    this.table = table;
    this.schema = schema;
    this.targetColumn =
        ConditionEvaluator.resolveColumn(List.of(schema), statement.columnName()).column();
    this.newValue = schema.parseValue(statement.value(), targetColumn);
    this.condition = ConditionEvaluator.bindCondition(List.of(schema), statement.whereCondition());
  }

  @Override
  public void open() throws IOException {
    iterator = null;
    LinkedHashMap<String, Row> updates = new LinkedHashMap<>();
    Set<String> keys = new HashSet<>();

    for (Table.Record record : table.scanRecords()) {
      Row row = record.row();
      if (!ConditionEvaluator.matches(ConditionEvaluator.qualifyRow(schema, row), condition)) {
        continue;
      }

      row.put(targetColumn.name(), newValue);
      table.validate(row);

      String newKey = schema.key(row);
      if (!keys.add(newKey) || (!newKey.equals(record.key()) && table.containsKey(newKey))) {
        throw new IllegalArgumentException("Key already exists: " + newKey);
      }
      updates.put(record.key(), row);
    }

    iterator = updates.entrySet().iterator();
  }

  @Override
  public Row next() throws IOException {
    if (iterator == null || !iterator.hasNext()) return null;

    Map.Entry<String, Row> update = iterator.next();
    table.update(update.getKey(), update.getValue());
    return update.getValue();
  }

  @Override
  public void close() {
    iterator = null;
  }
}
