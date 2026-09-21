package customdb.minidb.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PageManager implements AutoCloseable {
  private final DiskManager diskManager;
  private final FreeSpaceMap fsm;

  public PageManager(String dbFilePath) throws IOException {
    this.diskManager = new DiskManager(dbFilePath);
    this.fsm = new FreeSpaceMap();

    // 起動時に既存ページの空き容量を走査してFSMを構築
    buildFSM();
  }

  private void buildFSM() throws IOException {
    int numPages = diskManager.getPageCount();
    for (int i = 0; i < numPages; i++) {
      Page page = diskManager.readPage(i);
      fsm.addPage(page.getTotalFreeSpace());
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
  public void writePage(int pageNo, Page page) throws IOException {
    if (pageNo < 0) {
      throw new IllegalArgumentException("Page number cannot be negative: " + pageNo);
    }
    if (page == null) {
      throw new IllegalArgumentException("Page cannot be null.");
    }
    diskManager.writePage(pageNo, page);

    if (pageNo < getPageCount()) {
      fsm.updatePage(pageNo, page.getTotalFreeSpace());
    } else {
      fsm.addPage(page.getTotalFreeSpace());
    }
  }

  public Record findRecord(String key) throws IOException {
    if (key == null) {
      return null;
    }
    int numPages = getPageCount();
    for (int p = 0; p < numPages; p++) {
      Page page = readPage(p);
      for (int lpIndex = 0; lpIndex < page.getNumLinePointers(); lpIndex++) {
        Record record = page.getRecord(lpIndex);
        if (record != null && record.key().equals(key)) {
          return record;
        }
      }
    }
    return null;
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
    int numPages = getPageCount();
    for (int p = 0; p < numPages; p++) {
      Page page = readPage(p);
      for (int lpIndex = 0; lpIndex < page.getNumLinePointers(); lpIndex++) {
        Record record = page.getRecord(lpIndex);
        if (record != null && record.key().equals(key)) {
          page.deleteRecord(lpIndex);
          writePage(p, page);
          return true;
        }
      }
    }
    return false;
  }

  // 同一ページに収まらない場合は、旧データを削除してFSMで見つけた別ページへ再配置
  public boolean updateRecordByKey(String key, String newValue) throws IOException {
    if (key == null || newValue == null) {
      throw new IllegalArgumentException("Key and value cannot be null.");
    }
    Record newRecord = new Record(key, newValue);
    int requiredSpace = Page.calculateRequiredSpace(newRecord);
    if (requiredSpace > Page.MAX_RECORD_SIZE) {
      throw new IllegalArgumentException(
          "Record is too large. Max allowed: " + Page.MAX_RECORD_SIZE + " bytes.");
    }

    int numPages = getPageCount();
    for (int p = 0; p < numPages; p++) {
      Page page = readPage(p);
      for (int lpIndex = 0; lpIndex < page.getNumLinePointers(); lpIndex++) {
        Record record = page.getRecord(lpIndex);
        if (record != null && record.key().equals(key)) {
          int newLpIndex = page.updateRecord(lpIndex, newRecord);
          if (newLpIndex != -1) {
            writePage(p, page);
          } else {
            writePage(p, page);
            insertRecord(key, newValue);
          }
          return true;
        }
      }
    }
    return false;
  }

  public TupleId insertRecord(String key, String value) throws IOException {
    if (key == null || value == null) {
      throw new IllegalArgumentException("Key and value cannot be null.");
    }
    Record record = new Record(key, value);
    int requiredSpace = Page.calculateRequiredSpace(record);

    if (requiredSpace > Page.MAX_RECORD_SIZE) {
      throw new IllegalArgumentException(
          "Record is too large. Max allowed: " + Page.MAX_RECORD_SIZE + " bytes.");
    }

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
      fsm.updatePage(targetPageNo, page.getTotalFreeSpace());
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
