package customdb.chapter08.Transaction;

import java.io.IOException;

// トランザクションの開始・コミット・アボートを管理する
public class TransactionManager {
  private final LockManager lockManager = new LockManager();
  private Transaction currentTransaction;
  private long nextTransactionId = 1;

  public Transaction begin() {
    if (currentTransaction != null) {
      throw new IllegalStateException(
          "Transaction already active: tx"
              + currentTransaction.id()
              + ". Commit or abort it first.");
    }

    currentTransaction = new Transaction(nextTransactionId++);
    return currentTransaction;
  }

  public Transaction currentTransaction() {
    return currentTransaction;
  }

  public boolean hasActiveTransaction() {
    return currentTransaction != null;
  }

  // コミット：変更を確定してからロックを解放する
  public Transaction commit() {
    Transaction transaction = requireActiveTransaction();
    currentTransaction = null;
    transaction.commit();
    lockManager.releaseAll(transaction);
    return transaction;
  }

  // アボート：変更を取り消してからロックを解放する
  public int abort() throws IOException {
    Transaction transaction = requireActiveTransaction();
    currentTransaction = null;

    try {
      return transaction.abort();
    } finally {
      lockManager.releaseAll(transaction);
    }
  }

  public void acquireLock(Transaction transaction, String tableName, LockMode mode) {
    lockManager.acquire(transaction, tableName, mode);
  }

  private Transaction requireActiveTransaction() {
    if (currentTransaction == null) {
      throw new IllegalStateException("No active transaction.");
    }

    return currentTransaction;
  }
}
