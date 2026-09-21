package customdb.minidb.storage;

import java.util.ArrayList;
import java.util.List;

// 新規レコード挿入時に、ディスクを全走査することなく「どのページに収まるか」を高速に特定するために使用する
public class FreeSpaceMap {
  // インデックス = ページ番号, 値 = そのページの現在の空き容量（バイト）
  private final List<Integer> spaceMap;

  public FreeSpaceMap() {
    this.spaceMap = new ArrayList<>();
  }

  public void addPage(int freeSpace) {
    spaceMap.add(freeSpace);
  }

  // 既存ページの空き容量を更新する
  public void updatePage(int pageNo, int freeSpace) {
    if (pageNo >= 0 && pageNo < spaceMap.size()) {
      spaceMap.set(pageNo, freeSpace);
    }
  }

  // 指定した要求容量（requiredSpace）を格納可能な最初のページ番号を返す
  // 既存ページに収まらない場合は -1 を返す（呼び出し元は新規ページを追加する）
  public int getTargetPage(int requiredSpace) {
    for (int i = 0; i < spaceMap.size(); i++) {
      if (spaceMap.get(i) >= requiredSpace) {
        return i;
      }
    }
    return -1;
  }

  // 総ページ数
  public int getPageCount() {
    return spaceMap.size();
  }
}
