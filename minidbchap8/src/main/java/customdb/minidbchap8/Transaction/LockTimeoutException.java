package customdb.minidbchap8.Transaction;

// デッドロックなどでロックを取得できないまま一定時間が経過したときに投げる例外
public class LockTimeoutException extends RuntimeException {
  public LockTimeoutException(String message) {
    super(message);
  }
}
