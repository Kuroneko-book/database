package customdb.minidb.storage;

// データベース内におけるレコードの物理的な配置場所（アドレス）を表す識別子
// ページ番号（pageIndex）と、そのページ内のスロット番号（slotIndex）を保持する
public record TupleId(int pageIndex, int slotIndex) {
  @Override
  public String toString() {
    return "(Page: " + pageIndex + ", Slot: " + slotIndex + ")";
  }
}
