package customdb.minidb.storage;

import java.nio.ByteBuffer;

public class Page {
  public static final int PAGE_SIZE = 4096;

  // ヘッダサイズ: lower (2バイト) + upper (2バイト) = 4バイト
  public static final int HEADER_SIZE = 4;
  // 1つのラインポインタのサイズ (4バイト)
  public static final int LINE_POINTER_SIZE = 4;

  public static final int MAX_RECORD_SIZE = PAGE_SIZE - (HEADER_SIZE + LINE_POINTER_SIZE);

  // ラインポインタのフラグ定数 (PostgreSQL 互換)
  public static final int LP_UNUSED = 0; // 未使用スロット（新規データに再利用可能）
  public static final int LP_NORMAL = 1; // 通常の有効データ
  public static final int LP_DEAD = 3; // 削除済みデータ（ゴミ収集中）

  // ラインポインタのビットマスク: [31..17: データ長 15B] [16..15: フラグ 2B] [14..0: オフセット
  // 15B]
  private static final int OFFSET_MASK = 0x7FFF;
  private static final int FLAGS_MASK = 0x3;
  private static final int LENGTH_MASK = 0x7FFF;

  private final byte[] data;
  private final ByteBuffer buffer;

  public Page() {
    this.data = new byte[PAGE_SIZE];
    this.buffer = ByteBuffer.wrap(data);
    setLower(HEADER_SIZE);
    setUpper(PAGE_SIZE);
  }

  public Page(byte[] data) {
    if (data == null || data.length != PAGE_SIZE) {
      throw new IllegalArgumentException("Page data must be exactly " + PAGE_SIZE + " bytes.");
    }
    this.data = data;
    this.buffer = ByteBuffer.wrap(data);
  }

  public int getLower() {
    return Short.toUnsignedInt(buffer.getShort(0));
  }

  public void setLower(int lower) {
    buffer.putShort(0, (short) lower);
  }

  public int getUpper() {
    return Short.toUnsignedInt(buffer.getShort(2));
  }

  public void setUpper(int upper) {
    buffer.putShort(2, (short) upper);
  }

  public int getNumLinePointers() {
    return (getLower() - HEADER_SIZE) / LINE_POINTER_SIZE;
  }

  public int getLinePointer(int lpIndex) {
    return buffer.getInt(HEADER_SIZE + lpIndex * LINE_POINTER_SIZE);
  }

  public void setLinePointer(int lpIndex, int offset, int flags, int length) {
    int lp = (offset & OFFSET_MASK) | ((flags & FLAGS_MASK) << 15) | ((length & LENGTH_MASK) << 17);
    buffer.putInt(HEADER_SIZE + lpIndex * LINE_POINTER_SIZE, lp);
  }

  public static int lpOffset(int lp) {
    return lp & OFFSET_MASK;
  }

  public static int lpFlags(int lp) {
    return (lp >> 15) & FLAGS_MASK;
  }

  public static int lpLength(int lp) {
    return (lp >> 17) & LENGTH_MASK;
  }

  public boolean isLinePointerUsed(int lpIndex) {
    if (lpIndex < 0 || lpIndex >= getNumLinePointers()) {
      return false;
    }
    return lpFlags(getLinePointer(lpIndex)) == LP_NORMAL;
  }

  public boolean hasDeadTuples() {
    for (int i = 0; i < getNumLinePointers(); i++) {
      if (lpFlags(getLinePointer(i)) == LP_DEAD) {
        return true;
      }
    }
    return false;
  }

  // 指定したスロット番号から Record を読み出す
  public Record getRecord(int lpIndex) {
    if (lpIndex < 0 || lpIndex >= getNumLinePointers()) {
      return null;
    }

    int lp = getLinePointer(lpIndex);
    if (lpFlags(lp) != LP_NORMAL) {
      return null;
    }

    buffer.position(lpOffset(lp));
    return Record.deserialize(buffer);
  }

  public int insertRecord(Record record) {
    if (record == null) {
      throw new IllegalArgumentException("Record cannot be null.");
    }
    byte[] recordBytes = record.serialize();
    int requiredSpace = recordBytes.length;

    // 未使用スロット（LP_UNUSED）を探索
    int targetLpIndex = findUnusedSlot();
    boolean needNewLp = (targetLpIndex == -1);
    int totalSpaceNeeded = requiredSpace + (needNewLp ? LINE_POINTER_SIZE : 0);

    // 連続空き容量が不足しているか、スロットが満杯でLP_DEADが存在する場合、遅延コンパクションを実行
    if ((getFreeSpace() < totalSpaceNeeded || (needNewLp)) && hasDeadTuples()) {
      compact();
      targetLpIndex = findUnusedSlot();
      needNewLp = (targetLpIndex == -1);
      totalSpaceNeeded = requiredSpace + (needNewLp ? LINE_POINTER_SIZE : 0);
    }

    if (getFreeSpace() < totalSpaceNeeded) {
      // update出来ない時はここでエラーが返る
      return -1;
    }

    // 実データを upper の手前へ書き込み
    int newUpper = getUpper() - requiredSpace;
    setUpper(newUpper);

    buffer.position(newUpper);
    buffer.put(recordBytes);

    // 新規スロットの場合は lower を進める
    if (needNewLp) {
      targetLpIndex = getNumLinePointers();
      setLower(getLower() + LINE_POINTER_SIZE);
    }

    // ラインポインタを LP_NORMAL として記録
    setLinePointer(targetLpIndex, newUpper, LP_NORMAL, requiredSpace);
    return targetLpIndex;
  }

  private int findUnusedSlot() {
    for (int i = 0; i < getNumLinePointers(); i++) {
      if (lpFlags(getLinePointer(i)) == LP_UNUSED) {
        return i;
      }
    }
    return -1;
  }

  public boolean deleteRecord(int lpIndex) {
    if (lpIndex < 0 || lpIndex >= getNumLinePointers()) {
      return false;
    }

    int lp = getLinePointer(lpIndex);
    if (lpFlags(lp) == LP_NORMAL) {
      setLinePointer(lpIndex, lpOffset(lp), LP_DEAD, lpLength(lp));
      return true;
    }
    return false;
  }

  // レコードの更新。古いレコードを LP_DEAD にし、新しいレコードを挿入する
  public int updateRecord(int oldLpIndex, Record record) {
    if (record == null || oldLpIndex < 0 || oldLpIndex >= getNumLinePointers()) {
      return -1;
    }

    int oldLp = getLinePointer(oldLpIndex);
    if (lpFlags(oldLp) != LP_NORMAL) {
      return -1;
    }

    deleteRecord(oldLpIndex);
    return insertRecord(record);
  }

  // LP_NORMALの有効データのみをページ末尾（Upper側）に隙間なく詰め直す
  public void compact() {
    byte[] temp = new byte[PAGE_SIZE];
    ByteBuffer tempBuf = ByteBuffer.wrap(temp);

    System.arraycopy(this.data, 0, temp, 0, getLower());

    int currentUpper = PAGE_SIZE;

    for (int i = 0; i < getNumLinePointers(); i++) {
      int lp = getLinePointer(i);
      int flags = lpFlags(lp);

      if (flags == LP_NORMAL) {
        int len = lpLength(lp);
        int offset = lpOffset(lp);

        currentUpper -= len;
        System.arraycopy(this.data, offset, temp, currentUpper, len);

        int newLp =
            (currentUpper & OFFSET_MASK)
                | ((LP_NORMAL & FLAGS_MASK) << 15)
                | ((len & LENGTH_MASK) << 17);
        tempBuf.putInt(HEADER_SIZE + i * LINE_POINTER_SIZE, newLp);
      } else if (flags == LP_DEAD) {
        // LP_DEAD のデータ領域は破棄し、スロットを LP_UNUSED にリセット
        tempBuf.putInt(HEADER_SIZE + i * LINE_POINTER_SIZE, 0);
      }
    }

    System.arraycopy(temp, 0, this.data, 0, PAGE_SIZE);
    setUpper(currentUpper);
  }

  public byte[] getData() {
    return data;
  }

  public int getFreeSpace() {
    return getUpper() - getLower();
  }

  // LP_DEAD による回収可能領域を含めた、ページ内の潜在的な総空き容量（バイト数）を返す
  public int getTotalFreeSpace() {
    int free = getUpper() - getLower();
    for (int i = 0; i < getNumLinePointers(); i++) {
      int lp = getLinePointer(i);
      if (lpFlags(lp) == LP_DEAD) {
        free += lpLength(lp);
      }
    }
    return free;
  }

  // 1件のレコードを格納するために必要となる最大サイズ（データ長 +
  // ラインポインタ4バイト）を計算する
  public static int calculateRequiredSpace(Record record) {
    return record.getSerializedSize() + LINE_POINTER_SIZE;
  }
}
