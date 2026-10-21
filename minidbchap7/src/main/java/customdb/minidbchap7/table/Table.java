package customdb.minidbchap7.table;

import customdb.minidbchap7.index.BPlusTree;
import customdb.minidbchap7.storage.PageManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
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

  // 主キーの値 -> 行の内部キー
  private final Map<Object, Long> primaryIndex = new HashMap<>();
  private final BPlusTree rangeIndex;

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
    Long key = primaryIndex.get(value);
    if (key == null) {
      return List.of();
    }
    try {
      Row row = getRecord(new RecordId(key));
      if (row == null) {
        return List.of();
      }
      return List.of(row);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read record for key: " + key, e);
    }
  }

  public List<Row> searchRangeByIndex(String column, int value, String operator) {
    if (!hasIndex(column) || rangeIndex == null) {
      throw new IllegalStateException("No integer range index for column: " + column);
    }
    List<Row> rows = new ArrayList<>();
    for (Long key : rangeIndex.range(value, operator)) {
      try {
        Row row = getRecord(new RecordId(key));
        if (row != null) rows.add(row);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to read record for key: " + key, e);
      }
    }
    return rows;
  }

  public boolean hasIndex(String column) {
    Schema.Column indexed = schema.getColumn(column);
    return indexed != null && indexed.primaryKey();
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
