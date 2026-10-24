package customdb.minidbchap7.operator;

import customdb.minidbchap7.parser.Statement.Condition;
import customdb.minidbchap7.table.Row;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class NestedLoopJoinOperator implements Operator {
  private final Operator leftChild;
  private final Operator rightChild;
  private final Condition onCondition;

  private List<Row> rightRows;
  private Iterator<Row> rightIterator;
  private Row currentLeftRow;

  public NestedLoopJoinOperator(Operator leftChild, Operator rightChild, Condition onCondition) {
    this.leftChild = leftChild;
    this.rightChild = rightChild;
    this.onCondition = onCondition;
  }

  @Override
  public void open() throws IOException {
    leftChild.open();
    rightChild.open();

    // 右表は一度だけ走査
    rightRows = new ArrayList<>();
    while (true) {
      Row rightRow = rightChild.next();
      if (rightRow == null) {
        break;
      }
      rightRows.add(rightRow);
    }
    rightChild.close();

    rightIterator = rightRows.iterator();
    currentLeftRow = leftChild.next();
  }

  @Override
  public Row next() throws IOException {
    while (currentLeftRow != null) {
      if (!rightIterator.hasNext()) {

        rightIterator = rightRows.iterator();
        currentLeftRow = leftChild.next();
        continue;
      }
      Row rightRow = rightIterator.next();

      // JOIN結果には修飾キーだけをマージする
      Row joined = new Row();
      for (String key : currentLeftRow.keySet()) {
        if (key.contains(".")) joined.put(key, currentLeftRow.get(key));
      }
      for (String key : rightRow.keySet()) {
        if (key.contains(".")) joined.put(key, rightRow.get(key));
      }

      if (ConditionEvaluator.matches(joined, onCondition)) {
        return joined;
      }
    }
    return null;
  }

  @Override
  public void close() throws IOException {
    leftChild.close();
    rightChild.close();
    rightRows = null;
    rightIterator = null;
    currentLeftRow = null;
  }
}
