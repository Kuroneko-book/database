package customdb.minidb.operator;

import customdb.minidb.db.Row;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class NestedLoopJoinOperator implements Operator {
  private final Operator leftChild;
  private final Operator rightChild;
  private final ConditionEvaluator.BoundCondition onCondition;
  private final List<Row> rightRows;
  private Row currentLeft;
  private int rightIndex;
  private boolean opened;

  public NestedLoopJoinOperator(
      Operator leftChild, Operator rightChild, ConditionEvaluator.BoundCondition onCondition) {
    this.leftChild = leftChild;
    this.rightChild = rightChild;
    this.onCondition = onCondition;
    this.rightRows = new ArrayList<>();
  }

  @Override
  public void open() throws IOException {
    opened = false;
    rightRows.clear();
    currentLeft = null;
    rightIndex = 0;

    leftChild.open();
    rightChild.open();

    Row rightRow;
    while ((rightRow = rightChild.next()) != null) {
      rightRows.add(copyRow(rightRow));
    }

    opened = true;
  }

  @Override
  public Row next() throws IOException {
    if (!opened) {
      return null;
    }
    if (rightRows.isEmpty()) {
      opened = false;
      return null;
    }

    while (true) {
      if (currentLeft == null) {
        currentLeft = leftChild.next();
        rightIndex = 0;
        if (currentLeft == null) {
          opened = false;
          return null;
        }
      }

      while (rightIndex < rightRows.size()) {
        Row rightRow = rightRows.get(rightIndex++);
        Row joined = new Row();
        joined.putAll(currentLeft);
        joined.putAll(rightRow);
        if (ConditionEvaluator.matches(joined, onCondition)) {
          return joined;
        }
      }

      currentLeft = null;
    }
  }

  @Override
  public void close() throws IOException {
    Throwable failure = null;
    try {
      leftChild.close();
    } catch (IOException | RuntimeException e) {
      failure = e;
    }

    try {
      rightChild.close();
    } catch (IOException | RuntimeException e) {
      if (failure == null) {
        failure = e;
      } else if (failure != e) {
        failure.addSuppressed(e);
      }
    } finally {
      rightRows.clear();
      currentLeft = null;
      rightIndex = 0;
      opened = false;
    }

    if (failure != null) {
      if (failure instanceof IOException e) {
        throw e;
      }
      throw (RuntimeException) failure;
    }
  }

  @Override
  public String toString() {
    return leftChild.toString();
  }

  private Row copyRow(Row row) {
    Row copy = new Row();
    copy.putAll(row);
    return copy;
  }
}
