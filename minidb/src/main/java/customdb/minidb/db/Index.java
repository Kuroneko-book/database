package customdb.minidb.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Index {
  private static final int MINIMUM_DEGREE = 10;

  private final String primaryKeyColumn;
  private final BTree btree;

  public Index(String primaryKeyColumn) {
    this.primaryKeyColumn = Objects.requireNonNull(primaryKeyColumn);
    this.btree = new BTree(MINIMUM_DEGREE);
  }

  public void add(int key, Row row) {
    Objects.requireNonNull(row);
    List<Row> existing = btree.search(key);
    if (existing != null) {
      existing.add(copyRow(row));
      return;
    }
    List<Row> rows = new ArrayList<>();
    rows.add(copyRow(row));
    btree.insert(key, rows);
  }

  public void remove(int key, Row row) {
    Objects.requireNonNull(row);
    List<Row> rows = btree.search(key);
    if (rows == null) return;

    Object target = row.get(primaryKeyColumn);
    for (int i = 0; i < rows.size(); i++) {
      if (Objects.equals(target, rows.get(i).get(primaryKeyColumn))) {
        rows.remove(i);
        break;
      }
    }
    if (rows.isEmpty()) btree.delete(key);
  }

  public List<Row> search(int key) {
    List<Row> rows = btree.search(key);
    if (rows == null) return List.of();
    List<Row> result = new ArrayList<>(rows.size());
    for (Row row : rows) result.add(copyRow(row));
    return result;
  }

  private Row copyRow(Row row) {
    Row copy = new Row();
    copy.putAll(row);
    return copy;
  }

  private static final class BTree {
    private final int minimumDegree;
    private Node root;

    private BTree(int minimumDegree) {
      this.minimumDegree = minimumDegree;
      this.root = new Node(minimumDegree, true);
    }

    private List<Row> search(int key) {
      return search(root, key);
    }

    private List<Row> search(Node node, int key) {
      int index = lowerBound(node, key);
      if (index < node.keyCount && node.keys[index] == key) return node.values[index];
      return node.leaf ? null : search(node.children[index], key);
    }

    private void insert(int key, List<Row> rows) {
      if (root.keyCount == root.keys.length) {
        Node oldRoot = root;
        root = new Node(minimumDegree, false);
        root.children[0] = oldRoot;
        splitChild(root, 0);
      }
      Node node = root;
      while (true) {
        int index = lowerBound(node, key);
        if (index < node.keyCount && node.keys[index] == key) {
          node.values[index] = rows;
          return;
        }
        if (node.leaf) {
          insertAt(node, index, key, rows);
          return;
        }
        if (node.children[index].keyCount == node.children[index].keys.length) {
          splitChild(node, index);
          if (node.keys[index] == key) {
            node.values[index] = rows;
            return;
          }
          if (key > node.keys[index]) index++;
        }
        node = node.children[index];
      }
    }

    private void delete(int key) {
      delete(root, key);
      if (!root.leaf && root.keyCount == 0) root = root.children[0];
    }

    private void delete(Node node, int key) {
      int index = lowerBound(node, key);
      if (index < node.keyCount && node.keys[index] == key) {
        if (node.leaf) removeKey(node, index);
        else deleteInternalKey(node, index);
        return;
      }
      if (node.leaf) return;

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
        int removedKey = node.keys[index];
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

    private void insertAt(Node node, int index, int key, List<Row> rows) {
      System.arraycopy(node.keys, index, node.keys, index + 1, node.keyCount - index);
      System.arraycopy(node.values, index, node.values, index + 1, node.keyCount - index);
      node.keys[index] = key;
      node.values[index] = rows;
      node.keyCount++;
    }

    private void removeKey(Node node, int index) {
      int moved = node.keyCount - index - 1;
      System.arraycopy(node.keys, index + 1, node.keys, index, moved);
      System.arraycopy(node.values, index + 1, node.values, index, moved);
      node.keys[node.keyCount - 1] = 0;
      node.values[node.keyCount - 1] = null;
      node.keyCount--;
    }

    private void splitChild(Node parent, int index) {
      Node full = parent.children[index];
      Node right = new Node(minimumDegree, full.leaf);
      int middle = minimumDegree - 1;
      int rightKeys = minimumDegree - 1;
      int middleKey = full.keys[middle];
      List<Row> middleValue = full.values[middle];
      System.arraycopy(full.keys, minimumDegree, right.keys, 0, rightKeys);
      System.arraycopy(full.values, minimumDegree, right.values, 0, rightKeys);
      if (!full.leaf) {
        System.arraycopy(full.children, minimumDegree, right.children, 0, minimumDegree);
        for (int i = minimumDegree; i < full.children.length; i++) full.children[i] = null;
      }
      right.keyCount = rightKeys;
      full.keyCount = middle;
      for (int i = middle; i < full.keys.length; i++) {
        full.keys[i] = 0;
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
      sibling.keys[sibling.keyCount - 1] = 0;
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
      sibling.keys[sibling.keyCount - 1] = 0;
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
      parent.keys[parent.keyCount - 1] = 0;
      parent.values[parent.keyCount - 1] = null;
      parent.children[parent.keyCount] = null;
      parent.keyCount--;
    }

    private int lowerBound(Node node, int key) {
      int low = 0;
      int high = node.keyCount;
      while (low < high) {
        int middle = (low + high) >>> 1;
        if (node.keys[middle] < key) low = middle + 1;
        else high = middle;
      }
      return low;
    }

    private record KeyValue(int key, List<Row> value) {}

    private static final class Node {
      final int[] keys;
      final List<Row>[] values;
      final Node[] children;
      int keyCount;
      boolean leaf;

      @SuppressWarnings("unchecked")
      private Node(int minimumDegree, boolean leaf) {
        this.keys = new int[2 * minimumDegree - 1];
        this.values = (List<Row>[]) new List<?>[2 * minimumDegree - 1];
        this.children = new Node[2 * minimumDegree];
        this.leaf = leaf;
      }
    }
  }
}
