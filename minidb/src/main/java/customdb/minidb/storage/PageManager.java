package customdb.minidb.storage;

import customdb.minidb.index.BTree;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PageManager implements AutoCloseable {
  private final DiskManager diskManager;
  private final FreeSpaceMap fsm;
  private final BTree index;

  public PageManager(String dbFilePath) throws IOException {
    this(new DiskManager(dbFilePath));
  }

  PageManager(DiskManager diskManager) throws IOException {
    this.diskManager = diskManager;
    this.fsm = new FreeSpaceMap();
    this.index = new BTree();
    try {
      buildMetadata();
    } catch (IOException | RuntimeException e) {
      try {
        diskManager.close();
      } catch (IOException closeError) {
        e.addSuppressed(closeError);
      }
      throw e;
    }
  }

  private void buildMetadata() throws IOException {
    int numPages = diskManager.getPageCount();
    for (int p = 0; p < numPages; p++) {
      Page page = diskManager.readPage(p);
      fsm.addPage(page.getInsertableSpace());
      for (int lpIndex = 0; lpIndex < page.getNumLinePointers(); lpIndex++) {
        Record record = page.getRecord(lpIndex);
        if (record != null) {
          if (index.search(record.key()) != null) {
            throw new IOException("Duplicate key in database: " + record.key());
          }
          index.insert(record.key(), new TupleId(p, lpIndex));
        }
      }
    }
  }

  public int getPageCount() {
    return fsm.getPageCount();
  }

  // 指定したページをディスクから読み出す
  public Page readPage(int pageNo) throws IOException {
    if (pageNo < 0 || pageNo >= getPageCount()) {
      throw new IllegalArgumentException("Invalid page number: " + pageNo);
    }
    return diskManager.readPage(pageNo);
  }

  // ページをディスクへ書き込み、FSMの空き容量情報を最新化する
  private void writePage(int pageNo, Page page) throws IOException {
    if (pageNo < 0 || pageNo > getPageCount()) {
      throw new IllegalArgumentException("Invalid page number: " + pageNo);
    }
    if (page == null) {
      throw new IllegalArgumentException("Page cannot be null.");
    }
    diskManager.writePage(pageNo, page);

    if (pageNo < getPageCount()) {
      fsm.updatePage(pageNo, page.getInsertableSpace());
    } else {
      fsm.addPage(page.getInsertableSpace());
    }
  }

  public Record findRecord(String key) throws IOException {
    if (key == null) {
      return null;
    }
    TupleId tupleId = index.search(key);
    if (tupleId == null) {
      return null;
    }
    return indexedRecord(readPage(tupleId.pageIndex()), key, tupleId);
  }

  private Page readIndexedPage(String key, TupleId tupleId) throws IOException {
    Page page = readPage(tupleId.pageIndex());
    indexedRecord(page, key, tupleId);
    return page;
  }

  private Record indexedRecord(Page page, String key, TupleId tupleId) throws IOException {
    Record record = page.getRecord(tupleId.slotIndex());
    if (record == null || !record.key().equals(key)) {
      throw new IOException("Index does not match stored record: " + key);
    }
    return record;
  }

  // データベース内の全ページを走査し、すべての有効なレコードを返す
  public List<Record> findAllRecords() throws IOException {
    List<Record> records = new ArrayList<>();
    int numPages = getPageCount();
    for (int p = 0; p < numPages; p++) {
      Page page = readPage(p);
      for (int lpIndex = 0; lpIndex < page.getNumLinePointers(); lpIndex++) {
        Record record = page.getRecord(lpIndex);
        if (record != null) {
          records.add(record);
        }
      }
    }
    return records;
  }

  public boolean deleteRecordByKey(String key) throws IOException {
    if (key == null) {
      return false;
    }
    TupleId tupleId = index.search(key);
    if (tupleId == null) {
      return false;
    }
    Page page = readIndexedPage(key, tupleId);
    page.deleteRecord(tupleId.slotIndex());
    writePage(tupleId.pageIndex(), page);
    index.delete(key);
    return true;
  }

  public boolean updateRecordByKey(String key, String newValue) throws IOException {
    if (key == null || newValue == null) {
      throw new IllegalArgumentException("Key and value cannot be null.");
    }
    Record newRecord = new Record(key, newValue);
    int requiredSpace = newRecord.getSerializedSize();
    if (requiredSpace > Page.MAX_RECORD_SIZE) {
      throw new IllegalArgumentException(
          "Record is too large. Max allowed: " + Page.MAX_RECORD_SIZE + " bytes.");
    }

    TupleId oldId = index.search(key);
    if (oldId == null) {
      return false;
    }
    Page oldPage = readIndexedPage(key, oldId);
    int newLpIndex = oldPage.updateRecord(oldId.slotIndex(), newRecord);
    if (newLpIndex != -1) {
      writePage(oldId.pageIndex(), oldPage);
      index.insert(key, new TupleId(oldId.pageIndex(), newLpIndex));
      return true;
    }

    TupleId newId = storeRecord(newRecord);
    try {
      oldPage.deleteRecord(oldId.slotIndex());
      writePage(oldId.pageIndex(), oldPage);
    } catch (IOException e) {
      try {
        Page newPage = readPage(newId.pageIndex());
        newPage.deleteRecord(newId.slotIndex());
        writePage(newId.pageIndex(), newPage);
      } catch (IOException rollbackError) {
        e.addSuppressed(rollbackError);
      }
      throw e;
    }
    index.insert(key, newId);
    return true;
  }

  public TupleId insertRecord(String key, String value) throws IOException {
    if (key == null || value == null) {
      throw new IllegalArgumentException("Key and value cannot be null.");
    }
    Record record = new Record(key, value);
    int requiredSpace = record.getSerializedSize();
    if (requiredSpace > Page.MAX_RECORD_SIZE) {
      throw new IllegalArgumentException(
          "Record is too large. Max allowed: " + Page.MAX_RECORD_SIZE + " bytes.");
    }

    if (index.search(key) != null) {
      throw new IllegalArgumentException("Key already exists. Use update command to modify.");
    }
    TupleId tupleId = storeRecord(record);
    index.insert(key, tupleId);
    return tupleId;
  }

  private TupleId storeRecord(Record record) throws IOException {
    int requiredSpace = record.getSerializedSize();
    int targetPageNo = fsm.getTargetPage(requiredSpace);
    Page page;
    int slot = -1;

    if (targetPageNo != -1) {
      page = readPage(targetPageNo);
      slot = page.insertRecord(record);
      if (slot != -1) {
        writePage(targetPageNo, page);
        return new TupleId(targetPageNo, slot);
      }
      // 既存ページに収まらなかった場合、FSMを最新化してフォールバック
      fsm.updatePage(targetPageNo, page.getInsertableSpace());
    }

    // 新規ページを作成して挿入
    targetPageNo = getPageCount();
    page = new Page();
    slot = page.insertRecord(record);
    if (slot == -1) {
      throw new IOException("Failed to insert record: insufficient space in new page.");
    }
    writePage(targetPageNo, page);

    return new TupleId(targetPageNo, slot);
  }

  @Override
  public void close() throws IOException {
    if (diskManager != null) {
      diskManager.close();
    }
  }
}
