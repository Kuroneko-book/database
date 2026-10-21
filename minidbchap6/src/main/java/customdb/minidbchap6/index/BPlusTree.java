package customdb.minidbchap6.index;

import java.util.ArrayList;
import java.util.List;

/** 整数の主キーから行の内部キーを探す、メモリ上の B+ 木。値はリーフにだけ保持する。 */
public final class BPlusTree {
  private static final int DEFAULT_MINIMUM_DEGREE = 10;

  private final int minimumDegree;
  private final int maxKeys;
  private Node root;

  private final class Node {
    final boolean leaf;
    final int[] keys = new int[maxKeys + 1];
    final long[] values = new long[maxKeys + 1];
    final Node[] children = new BPlusTree.Node[maxKeys + 2];
    int keyCount;
    Node next;

    Node(boolean leaf) {
      this.leaf = leaf;
    }
  }

  public BPlusTree() {
    this(DEFAULT_MINIMUM_DEGREE);
  }

  public BPlusTree(int minimumDegree) {
    if (minimumDegree < 2) {
      throw new IllegalArgumentException("minimumDegree must be at least 2");
    }
    this.minimumDegree = minimumDegree;
    this.maxKeys = 2 * minimumDegree - 1;
  }

  public Long get(int key) {
    if (root == null) return null;
    Node leaf = findLeaf(key);
    int index = lowerBound(leaf, key);
    if (index < leaf.keyCount && leaf.keys[index] == key) {
      return leaf.values[index];
    }
    return null;
  }

  /** 既存キーの場合は行の内部キーだけを差し替える。 */
  public void put(int key, long recordId) {
    if (root == null) root = new Node(true);
    Node right = insert(root, key, recordId);
    if (right != null) {
      Node newRoot = new Node(false);
      newRoot.children[0] = root;
      newRoot.children[1] = right;
      newRoot.keyCount = 1;
      refresh(newRoot);
      root = newRoot;
    }
  }

  private Node insert(Node node, int key, long recordId) {
    if (node.leaf) {
      int i = lowerBound(node, key);
      if (i < node.keyCount && node.keys[i] == key) {
        node.values[i] = recordId;
        return null;
      }
      System.arraycopy(node.keys, i, node.keys, i + 1, node.keyCount - i);
      System.arraycopy(node.values, i, node.values, i + 1, node.keyCount - i);
      node.keys[i] = key;
      node.values[i] = recordId;
      node.keyCount++;
      if (node.keyCount > maxKeys) {
        return splitLeaf(node);
      }
      return null;
    }

    int i = upperBound(node, key);
    Node right = insert(node.children[i], key, recordId);
    if (right != null) {
      System.arraycopy(node.children, i + 1, node.children, i + 2, node.keyCount - i);
      node.children[i + 1] = right;
      node.keyCount++;
    }
    refresh(node);
    if (node.keyCount > maxKeys) {
      return splitInternal(node);
    }
    return null;
  }

  private Node splitLeaf(Node leaf) {
    Node right = new Node(true);
    int leftSize = minimumDegree;
    right.keyCount = leaf.keyCount - leftSize;
    System.arraycopy(leaf.keys, leftSize, right.keys, 0, right.keyCount);
    System.arraycopy(leaf.values, leftSize, right.values, 0, right.keyCount);
    leaf.keyCount = leftSize;
    right.next = leaf.next;
    leaf.next = right;
    return right;
  }

  private Node splitInternal(Node node) {
    Node right = new Node(false);
    int leftChildren = minimumDegree;
    int rightChildren = node.keyCount + 1 - leftChildren;
    System.arraycopy(node.children, leftChildren, right.children, 0, rightChildren);
    for (int i = leftChildren; i <= node.keyCount; i++) node.children[i] = null;
    node.keyCount = leftChildren - 1;
    right.keyCount = rightChildren - 1;
    refresh(node);
    refresh(right);
    return right;
  }

  public void delete(int key) {
    if (root == null || get(key) == null) return;
    delete(root, key);
    if (root.keyCount == 0) {
      if (root.leaf) {
        root = null;
      } else {
        root = root.children[0];
      }
    }
  }

  private void delete(Node node, int key) {
    if (node.leaf) {
      int i = lowerBound(node, key);
      System.arraycopy(node.keys, i + 1, node.keys, i, node.keyCount - i - 1);
      System.arraycopy(node.values, i + 1, node.values, i, node.keyCount - i - 1);
      node.keyCount--;
      return;
    }

    int i = upperBound(node, key);
    Node child = node.children[i];
    delete(child, key);
    if (child.keyCount < minimumDegree - 1) rebalance(node, i);
    if (node.keyCount > 0) refresh(node);
  }

  private void rebalance(Node parent, int i) {
    Node child = parent.children[i];
    if (i > 0 && parent.children[i - 1].keyCount >= minimumDegree) {
      Node left = parent.children[i - 1];
      if (child.leaf) {
        System.arraycopy(child.keys, 0, child.keys, 1, child.keyCount);
        System.arraycopy(child.values, 0, child.values, 1, child.keyCount);
        child.keys[0] = left.keys[left.keyCount - 1];
        child.values[0] = left.values[left.keyCount - 1];
      } else {
        System.arraycopy(child.children, 0, child.children, 1, child.keyCount + 1);
        child.children[0] = left.children[left.keyCount];
        left.children[left.keyCount] = null;
      }
      left.keyCount--;
      child.keyCount++;
      if (!child.leaf) {
        refresh(left);
        refresh(child);
      }
    } else if (i < parent.keyCount && parent.children[i + 1].keyCount >= minimumDegree) {
      Node right = parent.children[i + 1];
      if (child.leaf) {
        child.keys[child.keyCount] = right.keys[0];
        child.values[child.keyCount] = right.values[0];
        System.arraycopy(right.keys, 1, right.keys, 0, right.keyCount - 1);
        System.arraycopy(right.values, 1, right.values, 0, right.keyCount - 1);
      } else {
        child.children[child.keyCount + 1] = right.children[0];
        System.arraycopy(right.children, 1, right.children, 0, right.keyCount);
        right.children[right.keyCount] = null;
      }
      child.keyCount++;
      right.keyCount--;
      if (!child.leaf) {
        refresh(child);
        refresh(right);
      }
    } else {
      int leftIndex = i;
      if (i == parent.keyCount) {
        leftIndex = i - 1;
      }
      Node left = parent.children[leftIndex];
      Node right = parent.children[leftIndex + 1];
      if (left.leaf) {
        System.arraycopy(right.keys, 0, left.keys, left.keyCount, right.keyCount);
        System.arraycopy(right.values, 0, left.values, left.keyCount, right.keyCount);
        left.next = right.next;
      } else {
        System.arraycopy(right.children, 0, left.children, left.keyCount + 1, right.keyCount + 1);
      }
      if (left.leaf) {
        left.keyCount += right.keyCount;
      } else {
        left.keyCount += right.keyCount + 1;
      }
      if (!left.leaf) refresh(left);
      System.arraycopy(
          parent.children,
          leftIndex + 2,
          parent.children,
          leftIndex + 1,
          parent.keyCount - leftIndex - 1);
      parent.children[parent.keyCount] = null;
      parent.keyCount--;
    }
  }

  public List<Long> range(int key, String operator) {
    List<Long> result = new ArrayList<>();
    if (root == null) return result;
    if (operator.equals("=")) {
      Long value = get(key);
      if (value != null) result.add(value);
      return result;
    }

    Node leaf;
    if (operator.equals(">") || operator.equals(">=")) {
      leaf = findLeaf(key);
    } else {
      leaf = leftmostLeaf();
    }
    while (leaf != null) {
      for (int i = 0; i < leaf.keyCount; i++) {
        int current = leaf.keys[i];
        switch (operator) {
          case ">" -> {
            if (current > key) result.add(leaf.values[i]);
          }
          case ">=" -> {
            if (current >= key) result.add(leaf.values[i]);
          }
          case "<" -> {
            if (current >= key) return result;
            result.add(leaf.values[i]);
          }
          case "<=" -> {
            if (current > key) return result;
            result.add(leaf.values[i]);
          }
          default -> throw new IllegalArgumentException("Unsupported range operator: " + operator);
        }
      }
      leaf = leaf.next;
    }
    return result;
  }

  private Node findLeaf(int key) {
    Node node = root;
    while (!node.leaf) node = node.children[upperBound(node, key)];
    return node;
  }

  private Node leftmostLeaf() {
    Node node = root;
    while (!node.leaf) node = node.children[0];
    return node;
  }

  private int minKey(Node node) {
    while (!node.leaf) node = node.children[0];
    return node.keys[0];
  }

  private void refresh(Node node) {
    for (int i = 0; i < node.keyCount; i++) node.keys[i] = minKey(node.children[i + 1]);
  }

  private int lowerBound(Node node, int key) {
    int i = 0;
    while (i < node.keyCount && node.keys[i] < key) i++;
    return i;
  }

  private int upperBound(Node node, int key) {
    int i = 0;
    while (i < node.keyCount && node.keys[i] <= key) i++;
    return i;
  }
}
