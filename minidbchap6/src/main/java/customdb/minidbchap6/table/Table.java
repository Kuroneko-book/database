package customdb.minidbchap6.table;

import customdb.minidbchap6.index.BPlusTree;
import customdb.minidbchap6.storage.PageManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public class Table implements AutoCloseable {

  // ページ/スロット位置ではなく内部の単調増加キーで表す安定したレコード識別子
  public record RecordId(long key) {}

  public record Record(RecordId recordId, Row row) {}

  private final Schema schema;
  private final PageManager pageManager;

  // 主キーの値 -> 行の内部キー
  private final Map<Object, Long> primaryIndex = new HashMap<>();
  private final BPlusTree rangeIndex;

  // INDEX指定の副次索引: 列名 -> (列の値 -> 行の内部キーのリスト) 同じ値で複数行を保持
  private final Map<String, NavigableMap<Object, List<Long>>> secondaryIndexes = new HashMap<>();

  // 既存レコードの最大内部キー（空テーブルなら 0）。insert 時に +1 して採番する。
  private long maxKey;

  public Table(Schema schema, Path path) throws IOException {
    this.schema = schema;
    this.pageManager = new PageManager(path.toString());
    if (schema.primaryKeyColumn().type() == Schema.DataType.INTEGER) {
      this.rangeIndex = new BPlusTree();
    } else {
      this.rangeIndex = null;
    }

    // 主キー以外の INDEX 指定列ごとに副次索引を用意する（INTEGER は TreeMap で範囲検索も可能）
    for (Schema.Column column : schema.getColumns()) {
      if (column.isIndexed() && !column.primaryKey()) {
        secondaryIndexes.put(column.name(), new TreeMap<>());
      }
    }

    try {
      loadExistingRecords();
    } catch (IOException | RuntimeException e) {
      try {
        pageManager.close();
      } catch (IOException closeError) {
        e.addSuppressed(closeError);
      }
      throw e;
    }
  }

  // open時に既存レコードから内部キーの最大値と索引を再構築する
  private void loadExistingRecords() throws IOException {
    for (var storageRecord : pageManager.findAllRecords()) {
      Long key = decodeKey(storageRecord.key());
      Row row = RowCodec.decode(schema, storageRecord.value());
      if (key != null) {
        checkPrimaryKey(row, null);
        if (key > maxKey) {
          maxKey = key;
        }
        Object primaryValue = row.get(schema.primaryKeyColumn().name());
        primaryIndex.put(primaryValue, key);
        if (rangeIndex != null) {
          rangeIndex.put((Integer) primaryValue, key);
        }
        for (Schema.Column column : schema.getColumns()) {
          if (column.isIndexed() && !column.primaryKey()) {
            addSecondary(column.name(), row.get(column.name()), key);
          }
        }
      }
    }

    // データファイルの読み出し順に依存しないように副次索引の内部キーを昇順に整列する
    for (NavigableMap<Object, List<Long>> index : secondaryIndexes.values()) {
      for (List<Long> keys : index.values()) {
        keys.sort(Comparator.naturalOrder());
      }
    }
  }

  private Long decodeKey(String key) {
    try {
      return Long.parseLong(key);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public void insert(Row row) throws IOException {
    checkPrimaryKey(row, null);
    long key = maxKey + 1;
    byte[] value = RowCodec.encode(schema, row);

    pageManager.insertRecord(Long.toString(key), value);

    maxKey = key;
    Object primaryValue = row.get(schema.primaryKeyColumn().name());
    primaryIndex.put(primaryValue, key);
    if (rangeIndex != null) rangeIndex.put((Integer) primaryValue, key);
    for (Schema.Column column : schema.getColumns()) {
      if (column.isIndexed() && !column.primaryKey()) {
        addSecondary(column.name(), row.get(column.name()), key);
      }
    }
  }

  public List<Row> scan() throws IOException {
    List<Row> rows = new ArrayList<>();

    for (Record record : scanRecords()) {
      rows.add(record.row());
    }

    return rows;
  }

  public List<Record> scanRecords() throws IOException {
    List<Record> records = new ArrayList<>();

    for (var storageRecord : pageManager.findAllRecords()) {
      Long key = decodeKey(storageRecord.key());
      Row row = RowCodec.decode(schema, storageRecord.value());
      if (key != null) {
        records.add(new Record(new RecordId(key), row));
      }
    }

    records.sort(Comparator.comparingLong((Record r) -> r.recordId().key()));

    return records;
  }

  public void update(RecordId recordId, Row row) throws IOException {
    String key = encodeKey(recordId);

    var oldRecord = pageManager.findRecord(key);
    if (oldRecord == null) {
      throw new IllegalArgumentException("Record not found: " + recordId.key());
    }
    Row oldRow = RowCodec.decode(schema, oldRecord.value());

    checkPrimaryKey(row, recordId.key());

    if (!pageManager.updateRecordByKey(key, RowCodec.encode(schema, row))) {
      throw new IllegalArgumentException("Record not found: " + recordId.key());
    }

    Object oldPrimary = oldRow.get(schema.primaryKeyColumn().name());
    Object newPrimary = row.get(schema.primaryKeyColumn().name());
    primaryIndex.remove(oldPrimary);
    primaryIndex.put(newPrimary, recordId.key());
    if (rangeIndex != null && !oldPrimary.equals(newPrimary)) {
      rangeIndex.delete((Integer) oldPrimary);
      rangeIndex.put((Integer) newPrimary, recordId.key());
    }

    // 値が変わった副次索引列は古い値から削除して新しい値へ登録し直す
    for (String columnName : secondaryIndexes.keySet()) {
      Object oldValue = oldRow.get(columnName);
      Object newValue = row.get(columnName);
      if (!Objects.equals(oldValue, newValue)) {
        removeSecondary(columnName, oldValue, recordId.key());
        addSecondary(columnName, newValue, recordId.key());
      }
    }
  }

  public void delete(RecordId recordId) throws IOException {
    String key = encodeKey(recordId);
    var oldRecord = pageManager.findRecord(key);

    if (oldRecord == null) {
      return;
    }

    Row oldRow = RowCodec.decode(schema, oldRecord.value());
    pageManager.deleteRecordByKey(key);
    Object oldPrimary = oldRow.get(schema.primaryKeyColumn().name());
    primaryIndex.remove(oldPrimary);
    if (rangeIndex != null) rangeIndex.delete((Integer) oldPrimary);
    for (Schema.Column column : schema.getColumns()) {
      if (column.isIndexed() && !column.primaryKey()) {
        removeSecondary(column.name(), oldRow.get(column.name()), recordId.key());
      }
    }
  }

  public Row getRecord(RecordId recordId) throws IOException {
    var record = pageManager.findRecord(encodeKey(recordId));
    if (record == null) {
      return null;
    }
    return RowCodec.decode(schema, record.value());
  }

  public List<Row> searchByIndex(String column, Object value) {
    if (!hasIndex(column)) {
      throw new IllegalStateException("No index found for column: " + column);
    }
    Schema.Column indexedColumn = schema.getColumn(column);
    List<Long> keys;
    if (indexedColumn.primaryKey()) {
      // 主キーは一意なので内部キーは高々1つ
      Long key = primaryIndex.get(value);
      keys = key == null ? List.of() : List.of(key);
    } else {
      // 副次索引は同じ値に対して複数の内部キーを返す
      NavigableMap<Object, List<Long>> index = secondaryIndexes.get(indexedColumn.name());
      keys = index == null || value == null ? List.of() : index.getOrDefault(value, List.of());
    }
    List<Row> rows = new ArrayList<>();
    try {
      for (Long key : keys) {
        Row row = getRecord(new RecordId(key));
        if (row != null) {
          rows.add(row);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read record for key referenced by index", e);
    }
    return rows;
  }

  public List<Row> searchRangeByIndex(String column, int value, String operator) {
    if (!hasIndex(column)) {
      throw new IllegalStateException("No integer range index for column: " + column);
    }
    Schema.Column indexedColumn = schema.getColumn(column);
    List<Long> keys;
    if (indexedColumn.primaryKey()) {
      if (rangeIndex == null) {
        throw new IllegalStateException("No integer range index for column: " + column);
      }
      keys = rangeIndex.range(value, operator);
    } else {
      if (indexedColumn.type() != Schema.DataType.INTEGER) {
        throw new IllegalStateException("No integer range index for column: " + column);
      }
      keys = rangeFromSecondary(secondaryIndexes.get(indexedColumn.name()), value, operator);
    }
    List<Row> rows = new ArrayList<>();
    try {
      for (Long key : keys) {
        Row row = getRecord(new RecordId(key));
        if (row != null) rows.add(row);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read record for key referenced by index", e);
    }
    return rows;
  }

  // INTEGER の副次索引から範囲条件に合う内部キーを列の値の昇順で集める
  private List<Long> rangeFromSecondary(
      NavigableMap<Object, List<Long>> index, int value, String operator) {
    if (index == null) {
      return List.of();
    }
    List<Long> keys = new ArrayList<>();
    Iterable<List<Long>> matched =
        switch (operator) {
          case ">" -> index.tailMap(value, false).values();
          case ">=" -> index.tailMap(value, true).values();
          case "<" -> index.headMap(value, false).values();
          case "<=" -> index.headMap(value, true).values();
          default -> throw new IllegalArgumentException("Unsupported range operator: " + operator);
        };
    for (List<Long> keyList : matched) {
      keys.addAll(keyList);
    }
    return keys;
  }

  public boolean hasIndex(String column) {
    Schema.Column indexed = schema.getColumn(column);
    return indexed != null && indexed.isIndexed();
  }

  // 副次索引に内部キーを登録する。内部キーの昇順を保つためソート済み位置に挿入
  private void addSecondary(String columnName, Object value, long recordKey) {
    NavigableMap<Object, List<Long>> index = secondaryIndexes.get(columnName);
    if (index == null || value == null) {
      return;
    }
    List<Long> keys = index.computeIfAbsent(value, k -> new ArrayList<>());
    int i = 0;
    while (i < keys.size() && keys.get(i) < recordKey) i++;
    keys.add(i, recordKey);
  }

  // 副次索引から内部キーを取り除く　同じ値を持つ他の行の内部キーは残す
  private void removeSecondary(String columnName, Object value, long recordKey) {
    NavigableMap<Object, List<Long>> index = secondaryIndexes.get(columnName);
    if (index == null || value == null) {
      return;
    }
    List<Long> keys = index.get(value);
    if (keys == null) {
      return;
    }
    keys.remove(Long.valueOf(recordKey));
    if (keys.isEmpty()) {
      index.remove(value);
    }
  }

  private String encodeKey(RecordId recordId) {
    return Long.toString(recordId.key());
  }

  private void checkPrimaryKey(Row row, Long currentKey) {
    Schema.Column primaryKey = schema.primaryKeyColumn();
    Object value = row.get(primaryKey.name());
    if (value == null) {
      throw new IllegalArgumentException("Primary key value is required: " + primaryKey.name());
    }
    Long existingKey = primaryIndex.get(value);
    if (existingKey != null && !existingKey.equals(currentKey)) {
      throw new IllegalArgumentException("Duplicate primary key: " + value);
    }
  }

  @Override
  public void close() throws IOException {
    pageManager.close();
  }
}
