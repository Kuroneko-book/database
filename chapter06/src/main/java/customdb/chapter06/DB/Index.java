package customdb.chapter06.DB;

import java.util.ArrayList;
import java.util.List;

// B+ Tree implementation for indexing
public class Index {

  static class BTreeNode {
    boolean isLeaf;
    int numKeys;
    int[] keys;

    // leaf node fields
    List<Row>[] values; // only used in leaf nodes
    BTreeNode next; // pointer to the next leaf node (for range scans)

    // internal node fields
    BTreeNode[] children; // only used in internal nodes

    @SuppressWarnings("unchecked")
    BTreeNode(int t, boolean isLeaf) {
      this.isLeaf = isLeaf;
      this.keys = new int[2 * t - 1];
      if (isLeaf) {
        this.values = (List<Row>[]) new List[2 * t - 1];
      } else {
        this.children = new BTreeNode[2 * t];
      }
      this.numKeys = 0;
    }
  }

  static class BPlusTree {
    BTreeNode root;
    int t;

    BPlusTree(int t) {
      this.root = null;
      this.t = t;
    }

    public List<Row> search(int key) {
      return root == null ? List.of() : search(root, key);
    }

    private List<Row> search(BTreeNode node, int key) {
      int idx = findKey(node, key);
      if (node.isLeaf) {
        if (idx < node.numKeys && key == node.keys[idx]) {
          return node.values[idx] == null ? List.of() : node.values[idx];
        }
        return List.of();
      } else {
        return search(node.children[idx], key);
      }
    }

    private int findKey(BTreeNode node, int key) {
      int idx = 0;
      if (node.isLeaf) {
        while (idx < node.numKeys && key > node.keys[idx]) {
          idx++;
        }
      } else {
        while (idx < node.numKeys && key >= node.keys[idx]) {
          idx++;
        }
      }
      return idx;
    }

    public void insert(int key, Row row) {
      if (root == null) {
        root = new BTreeNode(t, true);
        root.keys[0] = key;
        List<Row> list = new ArrayList<>();
        list.add(row);
        root.values[0] = list;
        root.numKeys = 1;
      } else {
        if (root.numKeys == 2 * t - 1) {
          BTreeNode s = new BTreeNode(t, false);
          s.children[0] = root;
          splitChild(s, 0, root);

          int i = (s.keys[0] <= key) ? 1 : 0;
          insertNonFull(s.children[i], key, row);
          root = s;
        } else {
          insertNonFull(root, key, row);
        }
      }
    }

    private void insertNonFull(BTreeNode node, int key, Row row) {
      int i = node.numKeys - 1;
      if (node.isLeaf) {
        while (i >= 0 && node.keys[i] > key) {
          node.keys[i + 1] = node.keys[i];
          node.values[i + 1] = node.values[i];
          i--;
        }

        if (i >= 0 && node.keys[i] == key) {
          if (node.values[i] == null) {
            node.values[i] = new ArrayList<>();
          }
          node.values[i].add(row);
        } else {
          node.keys[i + 1] = key;
          List<Row> list = new ArrayList<>();
          list.add(row);
          node.values[i + 1] = list;
          node.numKeys++;
        }
      } else {
        while (i >= 0 && node.keys[i] > key) {
          i--;
        }
        i++;
        if (node.children[i].numKeys == 2 * t - 1) {
          splitChild(node, i, node.children[i]);
          if (node.keys[i] <= key) {
            i++;
          }
        }
        insertNonFull(node.children[i], key, row);
      }
    }

    private void splitChild(BTreeNode parent, int i, BTreeNode child) {
      BTreeNode newNode = new BTreeNode(t, child.isLeaf);

      if (child.isLeaf) {
        newNode.numKeys = t;
        for (int j = 0; j < t; j++) {
          newNode.keys[j] = child.keys[j + t - 1];
          newNode.values[j] = child.values[j + t - 1];
          child.values[j + t - 1] = null;
        }
        child.numKeys = t - 1;

        newNode.next = child.next;
        child.next = newNode;

        for (int j = parent.numKeys; j >= i + 1; j--) {
          parent.children[j + 1] = parent.children[j];
        }
        parent.children[i + 1] = newNode;

        for (int j = parent.numKeys - 1; j >= i; j--) {
          parent.keys[j + 1] = parent.keys[j];
        }
        parent.keys[i] = newNode.keys[0];
        parent.numKeys++;
      } else {
        newNode.numKeys = t - 1;
        for (int j = 0; j < t - 1; j++) {
          newNode.keys[j] = child.keys[j + t];
        }
        for (int j = 0; j < t; j++) {
          newNode.children[j] = child.children[j + t];
          child.children[j + t] = null;
        }
        child.numKeys = t - 1;

        for (int j = parent.numKeys; j >= i + 1; j--) {
          parent.children[j + 1] = parent.children[j];
        }
        parent.children[i + 1] = newNode;

        for (int j = parent.numKeys - 1; j >= i; j--) {
          parent.keys[j + 1] = parent.keys[j];
        }
        parent.keys[i] = child.keys[t - 1];
        parent.numKeys++;
      }
    }

    public void delete(int key) {
      if (root == null) return;

      delete(root, key);
      if (root.numKeys == 0) {
        if (root.isLeaf) {
          root = null;
        } else {
          root = root.children[0];
        }
      }
    }

    private void delete(BTreeNode node, int key) {
      int idx = findKey(node, key);

      if (node.isLeaf) {
        if (idx < node.numKeys && node.keys[idx] == key) {
          removeFromLeaf(node, idx);
        }
      } else {
        if (node.children[idx].numKeys < t) {
          fill(node, idx);
          idx = findKey(node, key);
        }
        delete(node.children[idx], key);
      }
    }

    private void removeFromLeaf(BTreeNode node, int idx) {
      for (int i = idx + 1; i < node.numKeys; i++) {
        node.keys[i - 1] = node.keys[i];
        node.values[i - 1] = node.values[i];
      }
      node.values[node.numKeys - 1] = null;
      node.numKeys--;
    }

    private void fill(BTreeNode node, int idx) {
      if (idx != 0 && node.children[idx - 1].numKeys >= t) {
        borrowFromPrev(node, idx);
      } else if (idx != node.numKeys && node.children[idx + 1].numKeys >= t) {
        borrowFromNext(node, idx);
      } else {
        if (idx != node.numKeys) {
          merge(node, idx);
        } else {
          merge(node, idx - 1);
        }
      }
    }

    private void borrowFromPrev(BTreeNode node, int idx) {
      BTreeNode child = node.children[idx];
      BTreeNode sibling = node.children[idx - 1];

      if (child.isLeaf) {
        for (int i = child.numKeys - 1; i >= 0; i--) {
          child.keys[i + 1] = child.keys[i];
          child.values[i + 1] = child.values[i];
        }
        child.keys[0] = sibling.keys[sibling.numKeys - 1];
        child.values[0] = sibling.values[sibling.numKeys - 1];
        sibling.values[sibling.numKeys - 1] = null;

        child.numKeys++;
        sibling.numKeys--;

        node.keys[idx - 1] = child.keys[0];
      } else {
        for (int i = child.numKeys - 1; i >= 0; i--) {
          child.keys[i + 1] = child.keys[i];
        }
        for (int i = child.numKeys; i >= 0; i--) {
          child.children[i + 1] = child.children[i];
        }

        child.keys[0] = node.keys[idx - 1];
        child.children[0] = sibling.children[sibling.numKeys];
        sibling.children[sibling.numKeys] = null;

        node.keys[idx - 1] = sibling.keys[sibling.numKeys - 1];

        child.numKeys++;
        sibling.numKeys--;
      }
    }

    private void borrowFromNext(BTreeNode node, int idx) {
      BTreeNode child = node.children[idx];
      BTreeNode sibling = node.children[idx + 1];

      if (child.isLeaf) {
        child.keys[child.numKeys] = sibling.keys[0];
        child.values[child.numKeys] = sibling.values[0];

        for (int i = 1; i < sibling.numKeys; i++) {
          sibling.keys[i - 1] = sibling.keys[i];
          sibling.values[i - 1] = sibling.values[i];
        }
        sibling.values[sibling.numKeys - 1] = null;

        child.numKeys++;
        sibling.numKeys--;

        node.keys[idx] = sibling.keys[0];
      } else {
        child.keys[child.numKeys] = node.keys[idx];
        child.children[child.numKeys + 1] = sibling.children[0];

        node.keys[idx] = sibling.keys[0];

        for (int i = 1; i < sibling.numKeys; i++) {
          sibling.keys[i - 1] = sibling.keys[i];
        }
        for (int i = 1; i <= sibling.numKeys; i++) {
          sibling.children[i - 1] = sibling.children[i];
        }
        sibling.children[sibling.numKeys] = null;

        child.numKeys++;
        sibling.numKeys--;
      }
    }

    private void merge(BTreeNode node, int idx) {
      BTreeNode child = node.children[idx];
      BTreeNode sibling = node.children[idx + 1];

      if (child.isLeaf) {
        for (int i = 0; i < sibling.numKeys; i++) {
          child.keys[i + child.numKeys] = sibling.keys[i];
          child.values[i + child.numKeys] = sibling.values[i];
          sibling.values[i] = null;
        }
        child.next = sibling.next;
        child.numKeys += sibling.numKeys;

        for (int i = idx + 1; i < node.numKeys; i++) {
          node.keys[i - 1] = node.keys[i];
        }
        for (int i = idx + 2; i <= node.numKeys; i++) {
          node.children[i - 1] = node.children[i];
        }
        node.children[node.numKeys] = null;
        node.numKeys--;
      } else {
        child.keys[t - 1] = node.keys[idx];

        for (int i = 0; i < sibling.numKeys; i++) {
          child.keys[i + t] = sibling.keys[i];
        }
        for (int i = 0; i <= sibling.numKeys; i++) {
          child.children[i + t] = sibling.children[i];
          sibling.children[i] = null;
        }

        child.numKeys += sibling.numKeys + 1;

        for (int i = idx + 1; i < node.numKeys; i++) {
          node.keys[i - 1] = node.keys[i];
        }
        for (int i = idx + 2; i <= node.numKeys; i++) {
          node.children[i - 1] = node.children[i];
        }
        node.children[node.numKeys] = null;
        node.numKeys--;
      }
    }
  }

  private final BPlusTree bplusTree;

  public Index() {
    // Select an appropriate minimum degree
    this.bplusTree = new BPlusTree(10);
  }

  public void add(Object keyObj, Row row) {
    if (keyObj == null) return;
    int key;
    if (keyObj instanceof Number number) {
      key = number.intValue();
    } else {
      try {
        key = Integer.parseInt(keyObj.toString());
      } catch (NumberFormatException e) {
        return;
      }
    }

    bplusTree.insert(key, row);
  }

  public List<Row> search(Object keyObj) {
    if (keyObj == null) return List.of();
    int key;
    if (keyObj instanceof Number number) {
      key = number.intValue();
    } else {
      try {
        key = Integer.parseInt(keyObj.toString());
      } catch (NumberFormatException e) {
        return List.of();
      }
    }
    return bplusTree.search(key);
  }

  public void remove(Object keyObj, Row row) {
    if (keyObj == null) {
      return;
    }

    int key;
    if (keyObj instanceof Number number) {
      key = number.intValue();
    } else {
      try {
        key = Integer.parseInt(keyObj.toString());
      } catch (NumberFormatException e) {
        return;
      }
    }

    List<Row> rows = bplusTree.search(key);
    if (!rows.isEmpty()) {
      rows.remove(row);
      if (rows.isEmpty()) {
        bplusTree.delete(key);
      }
    }
  }
}
