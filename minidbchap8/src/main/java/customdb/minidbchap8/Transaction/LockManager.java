package customdb.minidbchap8.Transaction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// テーブル単位のロックを管理する（共有ロックと排他ロック）
public class LockManager {
  // デッドロック対策：この時間を超えて待ってもロックが取れなければタイムアウトさせる
  private static final long LOCK_TIMEOUT_MILLIS = 10_000;

  // 動作確認用にロックの取得・解放を表示する
  private static final boolean LOG_LOCK_EVENTS = true;

  private final long lockTimeoutMillis;

  // 複数のトランザクション（スレッド）から同時に触られるため並行マップを使う
  private final Map<String, LockEntry> tableLocks = new ConcurrentHashMap<>();

  public LockManager() {
    this(LOCK_TIMEOUT_MILLIS);
  }

  // テストなどでタイムアウト時間を短くしたい場合に使う
  LockManager(long lockTimeoutMillis) {
    this.lockTimeoutMillis = lockTimeoutMillis;
  }

  // ロックを取得する。他のトランザクションと競合する場合は解放を待つ
  public void acquire(Transaction transaction, String tableName, LockMode mode) {
    String resource = tableName.toLowerCase();
    LockEntry entry = tableLocks.computeIfAbsent(resource, key -> new LockEntry());
    long deadline = System.currentTimeMillis() + lockTimeoutMillis;

    synchronized (entry) {
      if (alreadyHolds(entry, transaction, mode)) {
        return;
      }

      if (entry.waitQueue.isEmpty() && canGrant(entry, transaction, mode)) {
        grant(entry, transaction, mode, resource);
        return;
      }

      WaitRequest request = new WaitRequest(transaction, mode);
      entry.waitQueue.addLast(request);
      log("tx" + transaction.id() + " waits for " + mode + " lock on " + resource);

      while (true) {
        if (entry.waitQueue.peekFirst() == request && canGrant(entry, transaction, mode)) {
          entry.waitQueue.removeFirst();
          grant(entry, transaction, mode, resource);
          return;
        }

        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
          entry.waitQueue.remove(request);
          // 列の先頭が抜けたので、次の待機者がすぐ取得できるか確認させる
          entry.notifyAll();
          throw new LockTimeoutException(
              "Lock timeout: tx"
                  + transaction.id()
                  + " could not acquire "
                  + mode
                  + " lock on "
                  + resource
                  + " (possible deadlock)");
        }

        try {
          entry.wait(remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("Interrupted while waiting for lock: " + resource, e);
        }
      }
    }
  }

  // トランザクションが保持しているすべてのロックを解放する（Commit/Abort時に呼ぶ）
  public void releaseAll(Transaction transaction) {
    boolean released = false;

    for (LockEntry entry : tableLocks.values()) {
      synchronized (entry) {
        if (entry.holders.remove(transaction) != null) {
          released = true;
          entry.notifyAll();
        }
      }
    }

    if (released) {
      log("tx" + transaction.id() + " released all locks");
    }
  }

  // すでに同じか、より強いロックを保持しているか
  private boolean alreadyHolds(LockEntry entry, Transaction transaction, LockMode mode) {
    LockMode held = entry.holders.get(transaction);
    return held == LockMode.EXCLUSIVE || held == mode;
  }

  private boolean canGrant(LockEntry entry, Transaction transaction, LockMode mode) {
    if (entry.holders.isEmpty()) {
      return true;
    }

    if (mode == LockMode.SHARED) {
      return entry.holders.values().stream().allMatch(held -> held == LockMode.SHARED);
    }

    // 排他ロック：自分だけが共有ロックを持っている場合はアップグレードできる
    return entry.holders.size() == 1 && entry.holders.containsKey(transaction);
  }

  private void grant(LockEntry entry, Transaction transaction, LockMode mode, String resource) {
    LockMode held = entry.holders.put(transaction, mode);

    if (held == LockMode.SHARED) {
      log("tx" + transaction.id() + " upgraded to " + mode + " lock on " + resource);
    } else {
      log("tx" + transaction.id() + " acquired " + mode + " lock on " + resource);
    }

    entry.notifyAll();
  }

  private void log(String message) {
    if (LOG_LOCK_EVENTS) {
      System.out.println("[lock] " + message);
    }
  }

  private static class LockEntry {
    final Map<Transaction, LockMode> holders = new LinkedHashMap<>();
    final Deque<WaitRequest> waitQueue = new ArrayDeque<>();
  }

  private static class WaitRequest {
    final Transaction transaction;
    final LockMode mode;

    WaitRequest(Transaction transaction, LockMode mode) {
      this.transaction = transaction;
      this.mode = mode;
    }
  }
}
