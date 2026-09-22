package customdb.minidb.db;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class Row {
  private final Map<String, Object> values;

  public Row() {
    this.values = new LinkedHashMap<>();
  }

  public void put(String columnName, Object value) {
    values.put(columnName.toLowerCase(Locale.ROOT), value);
  }

  public Object get(String columnName) {
    return values.get(columnName.toLowerCase(Locale.ROOT));
  }

  public boolean contains(String columnName) {
    return values.containsKey(columnName.toLowerCase(Locale.ROOT));
  }

  public Set<String> keySet() {
    return values.keySet();
  }

  public Map<String, Object> getValues() {
    return values;
  }

  public void putAll(Row other) {
    values.putAll(other.values);
  }

  @Override
  public String toString() {
    return values.toString();
  }
}
