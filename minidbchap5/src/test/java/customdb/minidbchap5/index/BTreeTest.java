package customdb.minidbchap5.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import customdb.minidbchap5.storage.TupleId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class BTreeTest {
  @Test
  @DisplayName("insert / search: 挿入したキーを検索できる")
  void testInsertAndSearch() {
    BTree tree = new BTree();
    tree.insert("3", new TupleId(0, 3));
    tree.insert("1", new TupleId(0, 1));
    tree.insert("2", new TupleId(0, 2));

    assertEquals(new TupleId(0, 1), tree.search("1"));
    assertEquals(new TupleId(0, 2), tree.search("2"));
    assertEquals(new TupleId(0, 3), tree.search("3"));
    assertNull(tree.search("4"));
  }

  @Test
  @DisplayName("insert: 既存キーの上書き")
  void testInsertOverwritesExistingKey() {
    BTree tree = new BTree();
    tree.insert("1", new TupleId(0, 0));
    tree.insert("1", new TupleId(5, 9));
    assertEquals(new TupleId(5, 9), tree.search("1"));
  }

  @Test
  @DisplayName("insert: 閾値以上の要素数でノード分割が発生しても整合性を保つ")
  void testInsertTriggersSplits() {
    BTree tree = new BTree(3);
    for (int i = 0; i < 100; i++) {
      tree.insert(String.format("%03d", i), new TupleId(i / 10, i % 10));
    }
    for (int i = 0; i < 100; i++) {
      assertEquals(new TupleId(i / 10, i % 10), tree.search(String.format("%03d", i)));
    }
  }

  @Test
  @DisplayName("insert: 前順走査の順序で挿入しても動作する")
  void testSequentialInsert() {
    BTree tree = new BTree();
    for (int i = 0; i < 50; i++) {
      tree.insert(Integer.toString(i), new TupleId(0, i));
    }
    for (int i = 0; i < 50; i++) {
      assertEquals(new TupleId(0, i), tree.search(Integer.toString(i)));
    }
  }

  @Test
  @DisplayName("delete: リーフの削除と統合が正しく行われる")
  void testDelete() {
    BTree tree = new BTree(3);
    for (int i = 0; i < 20; i++) {
      tree.insert(String.format("%02d", i), new TupleId(0, i));
    }
    for (int i = 0; i < 10; i++) {
      tree.delete(String.format("%02d", i));
    }
    for (int i = 0; i < 10; i++) {
      assertNull(tree.search(String.format("%02d", i)));
    }
    for (int i = 10; i < 20; i++) {
      assertEquals(new TupleId(0, i), tree.search(String.format("%02d", i)));
    }
  }

  @Test
  @DisplayName("delete: 存在しないキーの削除は無視される")
  void testDeleteMissingKey() {
    BTree tree = new BTree();
    tree.insert("1", new TupleId(0, 0));
    tree.delete("999");
    assertEquals(new TupleId(0, 0), tree.search("1"));
    tree.delete("1");
    assertNull(tree.search("1"));
  }

  @Test
  @DisplayName("stress: ランダム操作がTreeMapの動作と一致する")
  void testRandomOperationsMatchReference() {
    BTree tree = new BTree(3);
    TreeMap<String, TupleId> reference = new TreeMap<>();
    Random random = new Random(42L);

    for (int round = 0; round < 2000; round++) {
      String key = String.format("%04d", random.nextInt(300));
      if (reference.isEmpty() || random.nextBoolean()) {
        TupleId value = new TupleId(random.nextInt(10), random.nextInt(100));
        tree.insert(key, value);
        reference.put(key, value);
      } else {
        tree.delete(key);
        reference.remove(key);
      }
      List<String> mismatches = new ArrayList<>();
      for (var entry : reference.entrySet()) {
        if (!entry.getValue().equals(tree.search(entry.getKey()))) {
          mismatches.add(entry.getKey());
        }
      }
      assertEquals(0, mismatches.size(), "mismatched keys: " + mismatches);
    }
  }

  @Test
  @DisplayName("constructor: 最小次数は2以上必須、nullは許可しない")
  void testConstraints() {
    assertThrows(IllegalArgumentException.class, () -> new BTree(1));
    assertThrows(NullPointerException.class, () -> new BTree().insert(null, new TupleId(0, 0)));
    assertThrows(NullPointerException.class, () -> new BTree().insert("1", null));
    assertThrows(NullPointerException.class, () -> new BTree().search(null));
    assertThrows(NullPointerException.class, () -> new BTree().delete(null));
  }
}
