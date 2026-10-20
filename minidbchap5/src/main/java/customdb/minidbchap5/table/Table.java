package customdb.minidbchap5.table;

import customdb.minidbchap5.storage.PageManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Table implements AutoCloseable {

  // ページ/スロット位置ではなく内部の単調増加キーで表す安定したレコード識別子
  public record RecordId(long key) {}

  public record Record(RecordId recordId, Row row) {}

  private final Schema schema;
  private final PageManager pageManager;

  // 索引は id カラムのみ: id値 -> 同値を持つ行の surrogate key のリスト
  private final Map<String, Map<Object, List<Long>>> indexes = new HashMap<>();

  // 既存レコードの最大内部キー（空テーブルなら 0）。insert 時に +1 して採番する。
  private long maxKey;

  public Table(Schema schema, Path path) throws IOException {
    this.schema = schema;
    this.pageManager = new PageManager(path.toString());

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
    for (Schema.Column column : schema.getColumns()) {
      if (column.isIndexed()) {
        indexes.put(column.name(), new HashMap<>());
      }
    }

    for (var storageRecord : pageManager.findAllRecords()) {
      Long key = decodeKey(storageRecord.key());
      Row row = RowCodec.decode(schema, storageRecord.value());
      if (key != null) {
        if (key > maxKey) {
          maxKey = key;
        }
        addToIndexes(key, row);
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
    long key = maxKey + 1;
    byte[] value = RowCodec.encode(schema, row);

    pageManager.insertRecord(Long.toString(key), value);

    maxKey = key;
    addToIndexes(key, row);
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

    if (!pageManager.updateRecordByKey(key, RowCodec.encode(schema, row))) {
      throw new IllegalArgumentException("Record not found: " + recordId.key());
    }

    removeFromIndexes(recordId.key(), oldRow);
    addToIndexes(recordId.key(), row);
  }

  public void delete(RecordId recordId) throws IOException {
    String key = encodeKey(recordId);
    var oldRecord = pageManager.findRecord(key);

    if (oldRecord == null) {
      return;
    }

    Row oldRow = RowCodec.decode(schema, oldRecord.value());
    pageManager.deleteRecordByKey(key);
    removeFromIndexes(recordId.key(), oldRow);
  }

  public Row getRecord(RecordId recordId) throws IOException {
    var record = pageManager.findRecord(encodeKey(recordId));
    return record == null ? null : RowCodec.decode(schema, record.value());
  }

  public List<Row> searchByIndex(String column, Object value) {
    Map<Object, List<Long>> index = indexFor(column);

    List<Long> keys = new ArrayList<>(index.getOrDefault(value, List.of()));
    Collections.sort(keys);

    List<Row> rows = new ArrayList<>();
    for (Long key : keys) {
      try {
        Row row = getRecord(new RecordId(key));
        if (row != null) {
          rows.add(row);
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to read record for key: " + key, e);
      }
    }

    return rows;
  }

  private Map<Object, List<Long>> indexFor(String column) {
    if (!hasIndex(column)) {
      throw new IllegalStateException("No index found for column: " + column);
    }

    return indexes.get(findIndexedColumn(column).name());
  }

  public boolean hasIndex(String column) {
    Schema.Column indexed = findIndexedColumn(column);
    return indexed != null && indexes.containsKey(indexed.name());
  }

  private Schema.Column findIndexedColumn(String column) {
    Schema.Column target = schema.getColumn(column);
    if (target == null || !target.isIndexed()) {
      return null;
    }
    return target;
  }

  private void addToIndexes(long key, Row row) {
    for (Schema.Column column : schema.getColumns()) {
      if (!column.isIndexed()) {
        continue;
      }

      Object value = row.get(column.name());
      indexes.get(column.name()).computeIfAbsent(value, ignored -> new ArrayList<>()).add(key);
    }
  }

  private void removeFromIndexes(long key, Row oldRow) {
    for (Schema.Column column : schema.getColumns()) {
      if (!column.isIndexed()) {
        continue;
      }

      Object value = oldRow.get(column.name());
      List<Long> keys = indexes.get(column.name()).get(value);

      if (keys != null) {
        keys.remove((Long) key);
        if (keys.isEmpty()) {
          indexes.get(column.name()).remove(value);
        }
      }
    }
  }

  private String encodeKey(RecordId recordId) {
    return Long.toString(recordId.key());
  }

  @Override
  public void close() throws IOException {
    pageManager.close();
  }
}
