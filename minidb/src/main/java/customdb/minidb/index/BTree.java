package customdb.minidb.index;

import customdb.minidb.storage.TupleId;
import java.util.Objects;

public final class BTree {
  static final int DEFAULT_MINIMUM_DEGREE = 10;

  static final class Node {
    final String[] keys;
    final TupleId[] values;
    final Node[] children;
    int keyCount;
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

  public TupleId search(String key) {
    Objects.requireNonNull(key, "key");
    return search(root, key);
  }

  private TupleId search(Node node, String key) {
    int index = lowerBound(node, key);
    if (index < node.keyCount && key.equals(node.keys[index])) {
      return node.values[index];
    }
    return node.leaf ? null : search(node.children[index], key);
  }

  public void insert(String key, TupleId value) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    if (root.keyCount == root.keys.length) {
      Node oldRoot = root;
      root = new Node(minimumDegree, false);
      root.children[0] = oldRoot;
      splitChild(root, 0);
    }
    Node existing = root;
    while (true) {
      int index = lowerBound(existing, key);
      if (index < existing.keyCount && key.equals(existing.keys[index])) {
        existing.values[index] = value;
        return;
      }
      if (existing.leaf) {
        insertAt(existing, index, key, value);
        return;
      }
      Node child = existing.children[index];
      if (child.keyCount == child.keys.length) {
        splitChild(existing, index);
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

  private void insertAt(Node node, int index, String key, TupleId value) {
    if (node.keyCount == node.keys.length) {
      throw new AssertionError("attempted to insert into a full node");
    }
    System.arraycopy(node.keys, index, node.keys, index + 1, node.keyCount - index);
    System.arraycopy(node.values, index, node.values, index + 1, node.keyCount - index);
    node.keys[index] = key;
    node.values[index] = value;
    node.keyCount++;
  }

  public void delete(String key) {
    Objects.requireNonNull(key, "key");
    delete(root, key);
    if (!root.leaf && root.keyCount == 0) {
      Node oldRoot = root;
      root = oldRoot.children[0];
      oldRoot.children[0] = null;
      oldRoot.leaf = true;
    }
  }

  private void delete(Node node, String key) {
    int index = lowerBound(node, key);
    if (index < node.keyCount && key.equals(node.keys[index])) {
      if (node.leaf) {
        removeKey(node, index);
      } else {
        deleteInternalKey(node, index);
      }
      return;
    }
    if (node.leaf) {
      return;
    }
    Node child = node.children[index];
    if (child.keyCount == minimumDegree - 1) {
      if (index > 0 && node.children[index - 1].keyCount >= minimumDegree) {
        borrowFromPrevious(node, index);
      } else if (index < node.keyCount && node.children[index + 1].keyCount >= minimumDegree) {
        borrowFromNext(node, index);
      } else if (index < node.keyCount) {
        mergeChildren(node, index);
      } else {
        mergeChildren(node, index - 1);
        index--;
      }
    }
    delete(node.children[index], key);
  }

  private void deleteInternalKey(Node node, int index) {
    Node left = node.children[index];
    Node right = node.children[index + 1];
    if (left.keyCount >= minimumDegree) {
      KeyValue predecessor = maximum(left);
      node.keys[index] = predecessor.key;
      node.values[index] = predecessor.value;
      delete(left, predecessor.key);
    } else if (right.keyCount >= minimumDegree) {
      KeyValue successor = minimum(right);
      node.keys[index] = successor.key;
      node.values[index] = successor.value;
      delete(right, successor.key);
    } else {
      String removedKey = node.keys[index];
      mergeChildren(node, index);
      delete(left, removedKey);
    }
  }

  private KeyValue minimum(Node node) {
    while (!node.leaf) node = node.children[0];
    return new KeyValue(node.keys[0], node.values[0]);
  }

  private KeyValue maximum(Node node) {
    while (!node.leaf) node = node.children[node.keyCount];
    return new KeyValue(node.keys[node.keyCount - 1], node.values[node.keyCount - 1]);
  }

  private record KeyValue(String key, TupleId value) {}

  private void removeKey(Node node, int index) {
    int moved = node.keyCount - index - 1;
    System.arraycopy(node.keys, index + 1, node.keys, index, moved);
    System.arraycopy(node.values, index + 1, node.values, index, moved);
    node.keys[node.keyCount - 1] = null;
    node.values[node.keyCount - 1] = null;
    node.keyCount--;
  }

  private void splitChild(Node parent, int index) {
    Node full = parent.children[index];
    Node right = new Node(minimumDegree, full.leaf);
    String middleKey = full.keys[minimumDegree - 1];
    TupleId middleValue = full.values[minimumDegree - 1];
    int rightKeys = minimumDegree - 1;
    System.arraycopy(full.keys, minimumDegree, right.keys, 0, rightKeys);
    System.arraycopy(full.values, minimumDegree, right.values, 0, rightKeys);
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
    System.arraycopy(
        parent.children, index + 1, parent.children, index + 2, parent.keyCount - index);
    parent.children[index + 1] = right;
    System.arraycopy(parent.keys, index, parent.keys, index + 1, parent.keyCount - index);
    System.arraycopy(parent.values, index, parent.values, index + 1, parent.keyCount - index);
    parent.keys[index] = middleKey;
    parent.values[index] = middleValue;
    parent.keyCount++;
  }

  private void borrowFromPrevious(Node parent, int index) {
    Node child = parent.children[index];
    Node sibling = parent.children[index - 1];
    System.arraycopy(child.keys, 0, child.keys, 1, child.keyCount);
    System.arraycopy(child.values, 0, child.values, 1, child.keyCount);
    if (!child.leaf) System.arraycopy(child.children, 0, child.children, 1, child.keyCount + 1);
    child.keys[0] = parent.keys[index - 1];
    child.values[0] = parent.values[index - 1];
    if (!child.leaf) child.children[0] = sibling.children[sibling.keyCount];
    parent.keys[index - 1] = sibling.keys[sibling.keyCount - 1];
    parent.values[index - 1] = sibling.values[sibling.keyCount - 1];
    sibling.keys[sibling.keyCount - 1] = null;
    sibling.values[sibling.keyCount - 1] = null;
    if (!sibling.leaf) sibling.children[sibling.keyCount] = null;
    sibling.keyCount--;
    child.keyCount++;
  }

  private void borrowFromNext(Node parent, int index) {
    Node child = parent.children[index];
    Node sibling = parent.children[index + 1];
    child.keys[child.keyCount] = parent.keys[index];
    child.values[child.keyCount] = parent.values[index];
    if (!child.leaf) child.children[child.keyCount + 1] = sibling.children[0];
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

  private void mergeChildren(Node parent, int index) {
    Node left = parent.children[index];
    Node right = parent.children[index + 1];
    int leftCount = left.keyCount;
    left.keys[leftCount] = parent.keys[index];
    left.values[leftCount] = parent.values[index];
    System.arraycopy(right.keys, 0, left.keys, leftCount + 1, right.keyCount);
    System.arraycopy(right.values, 0, left.values, leftCount + 1, right.keyCount);
    if (!left.leaf)
      System.arraycopy(right.children, 0, left.children, leftCount + 1, right.keyCount + 1);
    left.keyCount = leftCount + 1 + right.keyCount;
    int movedKeys = parent.keyCount - index - 1;
    System.arraycopy(parent.keys, index + 1, parent.keys, index, movedKeys);
    System.arraycopy(parent.values, index + 1, parent.values, index, movedKeys);
    System.arraycopy(
        parent.children, index + 2, parent.children, index + 1, parent.keyCount - index - 1);
    parent.keys[parent.keyCount - 1] = null;
    parent.values[parent.keyCount - 1] = null;
    parent.children[parent.keyCount] = null;
    parent.keyCount--;
    right.keyCount = 0;
    java.util.Arrays.fill(right.keys, null);
    java.util.Arrays.fill(right.values, null);
    java.util.Arrays.fill(right.children, null);
  }

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
