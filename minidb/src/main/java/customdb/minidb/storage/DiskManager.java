package customdb.minidb.storage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

public class DiskManager implements AutoCloseable {
  private final RandomAccessFile file;

  public DiskManager(String dbFilePath) throws IOException {
    Path path = Path.of(dbFilePath);
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    this.file = new RandomAccessFile(path.toFile(), "rw");
  }

  // 指定したページ番号の4KBデータをディスクから読み出し、Page オブジェクトとして返す
  public Page readPage(int pageNo) throws IOException {
    if (pageNo < 0) {
      throw new IllegalArgumentException("Page number cannot be negative: " + pageNo);
    }
    byte[] buf = new byte[Page.PAGE_SIZE];
    // ページ番号 × 4096バイトの位置へシーク
    file.seek((long) pageNo * Page.PAGE_SIZE);
    file.readFully(buf);
    return new Page(buf);
  }

  // 指定したページ番号の位置へ、Page オブジェクトの4KBバイト配列を書き込む
  public void writePage(int pageNo, Page page) throws IOException {
    if (pageNo < 0) {
      throw new IllegalArgumentException("Page number cannot be negative: " + pageNo);
    }
    if (page == null) {
      throw new IllegalArgumentException("Page cannot be null.");
    }
    file.seek((long) pageNo * Page.PAGE_SIZE);
    file.write(page.getData());
  }

  // 現在のファイルサイズから、保存されている総ページ数を計算して返す
  public int getPageCount() throws IOException {
    return (int) Math.ceil((double) file.length() / Page.PAGE_SIZE);
  }

  @Override
  public void close() throws IOException {
    if (file != null) {
      file.close();
    }
  }
}
