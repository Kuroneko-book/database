package customdb.chapter08.DB;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Table {
  private final Schema schema;
  private final Storage storage;

  public record RecordId(int pageNo, int slotNo) {}

  public record Record(RecordId recordId, Row row) {}

  public Table(Schema schema, Path path) throws IOException {
    this(schema, new FileStorage(path));
  }

  public Table(Schema schema, Storage storage) throws IOException {
    this.schema = schema;
    this.storage = storage;
    storage.initialize(schema);
  }

  public void truncate() throws IOException {
    storage.truncate();
  }

  public void insert(Row row) throws IOException {
    storage.insert(row);
  }

  public List<Row> scan() throws IOException {
    return storage.scan();
  }

  public List<Record> scanRecords() throws IOException {
    List<Record> records = new ArrayList<>();
    for (Storage.Record record : storage.scanRecords()) {
      records.add(
          new Record(
              new RecordId(record.recordId().pageNo(), record.recordId().slotNo()), record.row()));
    }
    return records;
  }

  public void update(RecordId recordId, Row row) throws IOException {
    storage.update(new Storage.RecordId(recordId.pageNo(), recordId.slotNo()), row);
  }

  public void delete(RecordId recordId) throws IOException {
    storage.delete(new Storage.RecordId(recordId.pageNo(), recordId.slotNo()));
  }

  public List<Row> searchByIndex(String column, Object value) throws IOException {
    return storage.searchByIndex(column, value);
  }

  public void close() throws IOException {
    storage.close();
  }
}
