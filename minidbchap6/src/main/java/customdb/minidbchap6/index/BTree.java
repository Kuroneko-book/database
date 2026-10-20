package customdb.minidbchap6.index;

import customdb.minidbchap6.storage.TupleId;
import java.util.Objects;

/**
 * キー（String）からレコードの位置（TupleId）への対応を管理する B-tree インデックス。
 *
 * <p>最小次数 t（minimumDegree）をパラメータとし、各ノードは最多で 2t - 1 個のキーを保持する。 ルート以外のノードは最低 t - 1
 * 個のキーを保持することを不変条件とするため、 削除時にも descend 前に兄弟からの借り入れやノード統合で下限を回復させておく。
 *
 * <p>挿入・検索・削除はすべて木の高さ（O(log n)）に比例した時間で行える。
 */
public final class BTree {
  static final int DEFAULT_MINIMUM_DEGREE = 10;

  // B-tree の1ノード。keys は常に昇順ソート済み。
  static final class Node {
    // ソート済みのキーと、それと並行した形で配置されたレコード位置
    final String[] keys;
    final TupleId[] values;

    // 子ノード。リーフノードでは参照しない
    final Node[] children;

    // keys / values のうち実際に使用している要素数
    int keyCount;

    // リーフノード（子を持たない）かどうか
    boolean leaf;

    Node(int minimumDegree, boolean leaf) {
      keys = new String[2 * minimumDegree - 1];
      values = new TupleId[2 * minimumDegree - 1];
      children = new Node[2 * minimumDegree];
      this.leaf = leaf;
    }
  }

  final int minimumDegree;
  Node root;

  public BTree() {
    this(DEFAULT_MINIMUM_DEGREE);
  }

  public BTree(int minimumDegree) {
    if (minimumDegree < 2) {
      throw new IllegalArgumentException("minimumDegree must be at least 2");
    }
    this.minimumDegree = minimumDegree;
    root = new Node(minimumDegree, true);
  }

  // 指定したキーに対応する TupleId を返す。存在しない場合は null を返す
  public TupleId search(String key) {
    Objects.requireNonNull(key, "key");
    return search(root, key);
  }

  // key 以上となる要素位置を二分探索で求め、対応する子へ降下しながら検索する
  private TupleId search(Node node, String key) {
    int index = lowerBound(node, key);

    // キーがこのノード内に見つかった場合、対応する値を返す
    if (index < node.keyCount && key.equals(node.keys[index])) {
      return node.values[index];
    }
    // リーフまで到達した場合は存在しない。そうでなければ該当する子を探索する
    return node.leaf ? null : search(node.children[index], key);
  }

  // キーと値を登録する。既にキーが存在する場合は値を差し替える（upsert）
  public void insert(String key, TupleId value) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");

    // ルートが満杯なら、木の高さが1つ増えるように分割する
    if (root.keyCount == root.keys.length) {
      Node oldRoot = root;
      root = new Node(minimumDegree, false);
      root.children[0] = oldRoot;
      splitChild(root, 0);
    }

    // 満杯の子に遭遇したら事前に分割しておく「先行分割」方式。
    // 降下中に必ず1つ余裕を持たせられるため、上向きの巻き戻しが不要になる
    Node existing = root;
    while (true) {
      int index = lowerBound(existing, key);

      // キーが既に存在するなら値のみを差し替えて終了
      if (index < existing.keyCount && key.equals(existing.keys[index])) {
        existing.values[index] = value;
        return;
      }
      // リーフに到達したら、あらかじめ確保済みの余裕へそのまま挿入する
      if (existing.leaf) {
        insertAt(existing, index, key, value);
        return;
      }
      // 内部ノードの場合、降下先の子が満杯なら分割して中間キーをここに昇格させる
      Node child = existing.children[index];
      if (child.keyCount == child.keys.length) {
        splitChild(existing, index);

        // 分割で昇格したキーと比較し、挿入位置が右半分なら子の参照を1つ進める
        int comparison = key.compareTo(existing.keys[index]);
        if (comparison == 0) {
          existing.values[index] = value;
          return;
        }
        if (comparison > 0) index++;
      }
      existing = existing.children[index];
    }
  }

  // 余裕のあるノードの index 番目へキーと値を挿入する（右にずらす）
  private void insertAt(Node node, int index, String key, TupleId value) {
    if (node.keyCount == node.keys.length) {
      throw new AssertionError("attempted to insert into a full node");
    }
    // index 以降の要素を1つ右へずらして空きを作る
    System.arraycopy(node.keys, index, node.keys, index + 1, node.keyCount - index);
    System.arraycopy(node.values, index, node.values, index + 1, node.keyCount - index);

    node.keys[index] = key;
    node.values[index] = value;
    node.keyCount++;
  }

  // 指定したキーを木から削除する。存在しない場合は何もしない
  public void delete(String key) {
    Objects.requireNonNull(key, "key");
    delete(root, key);

    // 統合により内部ルートがキーを持たなくなった場合、唯一の子を新しいルートにして高さを詰める
    if (!root.leaf && root.keyCount == 0) {
      Node oldRoot = root;
      root = oldRoot.children[0];
      oldRoot.children[0] = null;
      oldRoot.leaf = true;
    }
  }

  // lowerBound を用いてキー位置を特定し、対応する要素・子を削除する
  private void delete(Node node, String key) {
    int index = lowerBound(node, key);

    // キーがこのノード内にある場合
    if (index < node.keyCount && key.equals(node.keys[index])) {
      if (node.leaf) {
        removeKey(node, index);
      } else {
        deleteInternalKey(node, index);
      }
      return;
    }
    // リーフに到達したなら、そのキーは木に存在しない
    if (node.leaf) {
      return;
    }

    // 降下先の子を決める（下限は minimumDegree - 1 個）
    Node child = node.children[index];

    // 降下先の子が下限（t - 1 個）なら、降下前に余裕を持たせておく
    if (child.keyCount == minimumDegree - 1) {
      // 左の兄弟に余裕があればキーを借用する（borrowFromPrevious）
      if (index > 0 && node.children[index - 1].keyCount >= minimumDegree) {
        borrowFromPrevious(node, index);
      } else if (index < node.keyCount && node.children[index + 1].keyCount >= minimumDegree) {
        // 右の兄弟から借用する
        borrowFromNext(node, index);
      } else if (index < node.keyCount) {
        // どちらの兄弟にも余裕がないなら、右の兄弟と統合する
        mergeChildren(node, index);
      } else {
        // 最右の子だった場合のみ、左の兄弟と統合する
        mergeChildren(node, index - 1);
        index--;
      }
    }
    delete(node.children[index], key);
  }

  // 内部ノードの index 番目のキーを削除する
  private void deleteInternalKey(Node node, int index) {
    Node left = node.children[index];
    Node right = node.children[index + 1];

    // 左の子に十分なキーがある場合、左部分木の最大要素（先行要素）で置き換える
    if (left.keyCount >= minimumDegree) {
      KeyValue predecessor = maximum(left);
      node.keys[index] = predecessor.key();
      node.values[index] = predecessor.value();
      delete(left, predecessor.key());
    } else if (right.keyCount >= minimumDegree) {
      // 右の子に十分なキーがある場合、右部分木の最小要素（後続要素）で置き換える
      KeyValue successor = minimum(right);
      node.keys[index] = successor.key();
      node.values[index] = successor.value();
      delete(right, successor.key());
    } else {
      // 左右の子がいずれも下限なら、2つを統合してから統合後の子で削除を続ける
      String removedKey = node.keys[index];
      mergeChildren(node, index);
      delete(left, removedKey);
    }
  }

  // 部分木の最小要素（最左リーフの先頭）を返す
  private KeyValue minimum(Node node) {
    while (!node.leaf) node = node.children[0];
    return new KeyValue(node.keys[0], node.values[0]);
  }

  // 部分木の最大要素（最右リーフの末尾）を返す
  private KeyValue maximum(Node node) {
    while (!node.leaf) node = node.children[node.keyCount];
    return new KeyValue(node.keys[node.keyCount - 1], node.values[node.keyCount - 1]);
  }

  // キーと TupleId の組。先行・後続要素の取り出しに使用する
  private record KeyValue(String key, TupleId value) {}

  // リーフノードの index 番目を削除する（左にずらす）
  private void removeKey(Node node, int index) {
    int moved = node.keyCount - index - 1;
    System.arraycopy(node.keys, index + 1, node.keys, index, moved);
    System.arraycopy(node.values, index + 1, node.values, index, moved);
    node.keys[node.keyCount - 1] = null;
    node.values[node.keyCount - 1] = null;
    node.keyCount--;
  }

  // parent.children[index] が満杯（2t - 1 個）のとき、中間キーを親へ昇格させて左右に分割する
  private void splitChild(Node parent, int index) {
    Node full = parent.children[index];
    Node right = new Node(minimumDegree, full.leaf);

    // 中間キー（minimumDegree - 1 番目）を取り出しておく（親へ昇格させる）
    String middleKey = full.keys[minimumDegree - 1];
    TupleId middleValue = full.values[minimumDegree - 1];

    // 右側に残す要素（t - 1 個）を新しいノードへ移す
    int rightKeys = minimumDegree - 1;
    System.arraycopy(full.keys, minimumDegree, right.keys, 0, rightKeys);
    System.arraycopy(full.values, minimumDegree, right.values, 0, rightKeys);

    // 内部ノードなら子も右へ譲る。full 側の配列の後半はクリアしておく
    if (!full.leaf) {
      System.arraycopy(full.children, minimumDegree, right.children, 0, minimumDegree);
      for (int i = minimumDegree; i < full.children.length; i++) full.children[i] = null;
    }

    right.keyCount = rightKeys;
    full.keyCount = minimumDegree - 1;
    for (int i = full.keyCount; i < full.keys.length; i++) {
      full.keys[i] = null;
      full.values[i] = null;
    }

    // 親の子配列に1つ分の空きを作り、昇格した中間キーを挟み込む
    System.arraycopy(
        parent.children, index + 1, parent.children, index + 2, parent.keyCount - index);
    parent.children[index + 1] = right;
    System.arraycopy(parent.keys, index, parent.keys, index + 1, parent.keyCount - index);
    System.arraycopy(parent.values, index, parent.values, index + 1, parent.keyCount - index);
    parent.keys[index] = middleKey;
    parent.values[index] = middleValue;
    parent.keyCount++;
  }

  // 子が下限のとき、左兄弟の最大キーを経由して1要素分の余裕を貰う（右回転）
  private void borrowFromPrevious(Node parent, int index) {
    Node child = parent.children[index];
    Node sibling = parent.children[index - 1];
    System.arraycopy(child.keys, 0, child.keys, 1, child.keyCount);
    System.arraycopy(child.values, 0, child.values, 1, child.keyCount);
    if (!child.leaf) System.arraycopy(child.children, 0, child.children, 1, child.keyCount + 1);

    // 親の区切りキーを子の先頭へ降ろす
    child.keys[0] = parent.keys[index - 1];
    child.values[0] = parent.values[index - 1];
    if (!child.leaf) child.children[0] = sibling.children[sibling.keyCount];

    // 左兄弟の最大キーを親へ昇格させる
    parent.keys[index - 1] = sibling.keys[sibling.keyCount - 1];
    parent.values[index - 1] = sibling.values[sibling.keyCount - 1];
    sibling.keys[sibling.keyCount - 1] = null;
    sibling.values[sibling.keyCount - 1] = null;
    if (!sibling.leaf) sibling.children[sibling.keyCount] = null;
    sibling.keyCount--;
    child.keyCount++;
  }

  // 子が下限のとき、右兄弟の最小キーを経由して1要素分の余裕を貰う（左回転）
  private void borrowFromNext(Node parent, int index) {
    Node child = parent.children[index];
    Node sibling = parent.children[index + 1];

    // 親の区切りキーを子の末尾へ降ろす
    child.keys[child.keyCount] = parent.keys[index];
    child.values[child.keyCount] = parent.values[index];
    if (!child.leaf) child.children[child.keyCount + 1] = sibling.children[0];

    // 右兄弟の最小キーを親へ昇格させる
    parent.keys[index] = sibling.keys[0];
    parent.values[index] = sibling.values[0];
    System.arraycopy(sibling.keys, 1, sibling.keys, 0, sibling.keyCount - 1);
    System.arraycopy(sibling.values, 1, sibling.values, 0, sibling.keyCount - 1);
    sibling.keys[sibling.keyCount - 1] = null;
    sibling.values[sibling.keyCount - 1] = null;
    if (!sibling.leaf) {
      System.arraycopy(sibling.children, 1, sibling.children, 0, sibling.keyCount);
      sibling.children[sibling.keyCount] = null;
    }
    sibling.keyCount--;
    child.keyCount++;
  }

  // 下限の2つの子を、間の親キーとともに1つのノードへ統合する
  private void mergeChildren(Node parent, int index) {
    Node left = parent.children[index];
    Node right = parent.children[index + 1];
    int leftCount = left.keyCount;

    // 親の区切りキーを左ノードの末尾に降ろし、右ノードの中身を続けて連結する
    left.keys[leftCount] = parent.keys[index];
    left.values[leftCount] = parent.values[index];
    System.arraycopy(right.keys, 0, left.keys, leftCount + 1, right.keyCount);
    System.arraycopy(right.values, 0, left.values, leftCount + 1, right.keyCount);
    if (!left.leaf)
      System.arraycopy(right.children, 0, left.children, leftCount + 1, right.keyCount + 1);
    left.keyCount = leftCount + 1 + right.keyCount;

    // 統合で1つ余分になった親の要素・子を左へずらして詰める
    int movedKeys = parent.keyCount - index - 1;
    System.arraycopy(parent.keys, index + 1, parent.keys, index, movedKeys);
    System.arraycopy(parent.values, index + 1, parent.values, index, movedKeys);
    System.arraycopy(
        parent.children, index + 2, parent.children, index + 1, parent.keyCount - index - 1);
    parent.keys[parent.keyCount - 1] = null;
    parent.values[parent.keyCount - 1] = null;
    parent.children[parent.keyCount] = null;
    parent.keyCount--;

    // 統合で不要になった右ノードは使い捨てなので、内容を空にしておく
    right.keyCount = 0;
    java.util.Arrays.fill(right.keys, null);
    java.util.Arrays.fill(right.values, null);
    java.util.Arrays.fill(right.children, null);
  }

  // ソート済み keys の中で、key 以上となる最初のインデックスを二分探索で求める
  private int lowerBound(Node node, String key) {
    int low = 0;
    int high = node.keyCount;
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (node.keys[middle].compareTo(key) < 0) low = middle + 1;
      else high = middle;
    }
    return low;
  }
}
