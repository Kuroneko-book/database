package customdb.chapter08.DB;

import java.io.IOException;
import java.util.List;

public interface Storage extends AutoCloseable {
  void initialize(Schema schema) throws IOException;

  void truncate() throws IOException;

  void insert(Row row) throws IOException;

  List<Row> scan() throws IOException;

  List<Record> scanRecords() throws IOException;

  void update(RecordId recordId, Row row) throws IOException;

  void delete(RecordId recordId) throws IOException;

  List<Row> searchByIndex(String column, Object value) throws IOException;

  @Override
  void close() throws IOException;

  record RecordId(int pageNo, int slotNo) {}

  record Record(RecordId recordId, Row row) {}
}
