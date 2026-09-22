package customdb.minidb.db;

import customdb.minidb.storage.Page;
import customdb.minidb.storage.PageManager;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Table implements AutoCloseable {
  private final Schema schema;
  private final PageManager pageManager;
  private final Map<String, Index> indexes;

  public record Record(String key, Row row) {}

  public Table(Schema schema, Path path) throws IOException {
    if (schema == null || path == null) {
      throw new IllegalArgumentException("Schema and path cannot be null.");
    }

    this.schema = schema;
    this.pageManager = new PageManager(path.toString());
    this.indexes = new LinkedHashMap<>();

    try {
      for (Schema.Column column : schema.getColumns()) {
        if (column.indexed() && column.type() == Schema.DataType.INTEGER) {
          indexes.put(column.name(), new Index(schema.getKeyColumn().name()));
        }
      }

      if (!indexes.isEmpty()) {
        for (Record record : scanRecords()) {
          addToIndexes(record.row());
        }
      }
    } catch (IOException | RuntimeException e) {
      try {
        pageManager.close();
      } catch (IOException closeError) {
        e.addSuppressed(closeError);
      }
      throw e;
    }
  }

  public void insert(Row row) throws IOException {
    byte[] payload = validatedPayload(row);
    String key = schema.key(row);

    pageManager.insertRecord(key, payload);
    addToIndexes(row);
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

    for (customdb.minidb.storage.Record stored : pageManager.findAllRecords()) {
      Row row = deserialize(stored.value());
      if (!schema.key(row).equals(stored.key())) {
        throw new IOException("Stored key does not match row key: " + stored.key());
      }

      records.add(new Record(stored.key(), row));
    }

    return records;
  }

  public boolean update(String oldKey, Row row) throws IOException {
    byte[] payload = validatedPayload(row);
    if (indexes.isEmpty()) {
      return pageManager.updateRecordByKey(oldKey, schema.key(row), payload);
    }
    if (oldKey == null) {
      throw new IllegalArgumentException("Key cannot be null.");
    }

    customdb.minidb.storage.Record oldRecord = pageManager.findRecord(oldKey);
    if (oldRecord == null) {
      return false;
    }

    Row oldRow = deserialize(oldRecord.value());
    boolean updated = pageManager.updateRecordByKey(oldKey, schema.key(row), payload);
    if (updated) {
      removeFromIndexes(oldRow);
      addToIndexes(row);
    }

    return updated;
  }

  public boolean delete(String key) throws IOException {
    if (indexes.isEmpty()) {
      return pageManager.deleteRecordByKey(key);
    }

    customdb.minidb.storage.Record oldRecord = pageManager.findRecord(key);
    if (oldRecord == null) {
      return false;
    }

    Row oldRow = deserialize(oldRecord.value());
    boolean deleted = pageManager.deleteRecordByKey(key);
    if (deleted) {
      removeFromIndexes(oldRow);
    }

    return deleted;
  }

  public List<Row> searchByIndex(String column, int value) {
    Schema.Column schemaColumn = schema.getColumn(column);
    if (schemaColumn == null || !indexes.containsKey(schemaColumn.name())) {
      throw new IllegalArgumentException("Column is not indexed: " + column);
    }

    return indexes.get(schemaColumn.name()).search(value);
  }

  public void validate(Row row) {
    validatedPayload(row);
  }

  private byte[] validatedPayload(Row row) {
    schema.validate(row);

    byte[] payload = serialize(row);
    customdb.minidb.storage.Record physical =
        new customdb.minidb.storage.Record(schema.key(row), payload);
    if (physical.getSerializedSize() > Page.MAX_RECORD_SIZE) {
      throw new IllegalArgumentException(
          "Record is too large. Max allowed: " + Page.MAX_RECORD_SIZE + " bytes.");
    }

    return payload;
  }

  public boolean containsKey(String key) throws IOException {
    return pageManager.findRecord(key) != null;
  }

  private void addToIndexes(Row row) {
    for (Map.Entry<String, Index> entry : indexes.entrySet()) {
      entry.getValue().add((Integer) row.get(entry.getKey()), row);
    }
  }

  private void removeFromIndexes(Row row) {
    for (Map.Entry<String, Index> entry : indexes.entrySet()) {
      entry.getValue().remove((Integer) row.get(entry.getKey()), row);
    }
  }

  private byte[] serialize(Row row) {
    long size = 0;
    for (Schema.Column column : schema.getColumns()) {
      Object value = row.get(column.name());
      size +=
          switch (column.type()) {
            case INTEGER, FLOAT -> 4;
            case DOUBLE -> 8;
            case STRING -> 4L + ((String) value).getBytes(StandardCharsets.UTF_8).length;
          };
      if (size > Page.MAX_RECORD_SIZE) {
        throw new IllegalArgumentException("Record is too large.");
      }
    }

    ByteBuffer buffer = ByteBuffer.allocate((int) size);
    for (Schema.Column column : schema.getColumns()) {
      Object value = row.get(column.name());
      switch (column.type()) {
        case INTEGER -> buffer.putInt((Integer) value);
        case FLOAT -> buffer.putFloat((Float) value);
        case DOUBLE -> buffer.putDouble((Double) value);
        case STRING -> {
          byte[] bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
          buffer.putInt(bytes.length).put(bytes);
        }
      }
    }

    return buffer.array();
  }

  private Row deserialize(byte[] payload) throws IOException {
    if (payload == null) {
      throw new IOException("Null record payload");
    }

    ByteBuffer buffer = ByteBuffer.wrap(payload);
    Row row = new Row();

    try {
      for (Schema.Column column : schema.getColumns()) {
        switch (column.type()) {
          case INTEGER -> row.put(column.name(), buffer.getInt());
          case FLOAT -> row.put(column.name(), buffer.getFloat());
          case DOUBLE -> row.put(column.name(), buffer.getDouble());
          case STRING -> {
            int length = buffer.getInt();
            if (length < 0 || length > buffer.remaining()) {
              throw new IOException("Invalid string length in record");
            }

            byte[] bytes = new byte[length];
            buffer.get(bytes);
            row.put(column.name(), decodeUtf8(bytes));
          }
        }
      }
    } catch (java.nio.BufferUnderflowException | java.nio.BufferOverflowException e) {
      throw new IOException("Truncated record payload", e);
    }

    if (buffer.hasRemaining()) {
      throw new IOException("Trailing bytes in record payload");
    }

    try {
      schema.validate(row);
    } catch (RuntimeException e) {
      throw new IOException("Invalid record payload", e);
    }

    return row;
  }

  private static String decodeUtf8(byte[] bytes) throws IOException {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw new IOException("Invalid UTF-8 string in record", e);
    }
  }

  @Override
  public void close() throws IOException {
    pageManager.close();
  }
}
