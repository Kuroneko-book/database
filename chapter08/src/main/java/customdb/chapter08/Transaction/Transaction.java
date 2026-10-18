package customdb.chapter08.Transaction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// 1つのトランザクション（一連の操作のまとまり）を表す
public class Transaction {
  // Abort時に実行する「変更を取り消す操作」
  public interface UndoAction {
    void undo() throws IOException;
  }

  public enum State {
    ACTIVE,
    COMMITTED,
    ABORTED
  }

  private final long id;
  private final List<UndoAction> undoLog = new ArrayList<>();
  private State state = State.ACTIVE;

  public Transaction(long id) {
    this.id = id;
  }

  public long id() {
    return id;
  }

  public State state() {
    return state;
  }

  // 変更を取り消すための操作を登録する（Abort時に登録と逆順で実行する）
  public void addUndo(UndoAction undoAction) {
    if (state != State.ACTIVE) {
      throw new IllegalStateException("Transaction is not active: tx" + id);
    }
    undoLog.add(undoAction);
  }

  // コミット：変更を確定し、取り消し情報は不要になるので破棄する
  public void commit() {
    state = State.COMMITTED;
    undoLog.clear();
  }

  // アボート：登録された操作を逆順に実行して、トランザクション開始前の状態に戻す
  public int abort() throws IOException {
    for (int i = undoLog.size() - 1; i >= 0; i--) {
      undoLog.get(i).undo();
    }
    int undone = undoLog.size();
    undoLog.clear();
    state = State.ABORTED;
    return undone;
  }
}
