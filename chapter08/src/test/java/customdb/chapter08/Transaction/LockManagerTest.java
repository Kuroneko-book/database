package customdb.chapter08.Transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 複数のトランザクション（スレッド）を同時に動かし、ロック機構の動作を確認する
class LockManagerTest {
  // テストを素早く終えるために、通常の10秒ではなく短いタイムアウトを使う
  private static final long TEST_TIMEOUT_MILLIS = 1000;

  @Test
  @DisplayName("共有ロックは複数のトランザクションが同時に取得できる")
  void sharedLocksAreCompatible() throws Exception {
    LockManager lockManager = new LockManager(TEST_TIMEOUT_MILLIS);
    Transaction tx1 = new Transaction(1);
    Transaction tx2 = new Transaction(2);
    lockManager.acquire(tx1, "users", LockMode.SHARED);

    AtomicReference<Throwable> thrown = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    startAcquire(lockManager, tx2, "users", LockMode.SHARED, thrown, done);

    assertTrue(done.await(2, TimeUnit.SECONDS), "共有ロック同士は待たずに取得できる");
    assertNull(thrown.get());
  }

  @Test
  @DisplayName("排他ロックの保持中は共有ロックの取得が待たされ、解放後に取得できる")
  void exclusiveLockBlocksSharedLock() throws Exception {
    LockManager lockManager = new LockManager(TEST_TIMEOUT_MILLIS);
    Transaction tx1 = new Transaction(1);
    Transaction tx2 = new Transaction(2);
    lockManager.acquire(tx1, "users", LockMode.EXCLUSIVE);

    AtomicReference<Throwable> thrown = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    startAcquire(lockManager, tx2, "users", LockMode.SHARED, thrown, done);

    assertFalse(done.await(150, TimeUnit.MILLISECONDS), "排他ロック中は共有ロックを取得できない");

    lockManager.releaseAll(tx1);

    assertTrue(done.await(2, TimeUnit.SECONDS), "解放後は取得できる");
    assertNull(thrown.get());
  }

  @Test
  @DisplayName("共有ロックの保持中は排他ロックの取得が待たされる（ロストアップデートの防止）")
  void sharedLockBlocksExclusiveLock() throws Exception {
    LockManager lockManager = new LockManager(TEST_TIMEOUT_MILLIS);
    Transaction tx1 = new Transaction(1);
    Transaction tx2 = new Transaction(2);
    lockManager.acquire(tx1, "users", LockMode.SHARED);

    AtomicReference<Throwable> thrown = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    startAcquire(lockManager, tx2, "users", LockMode.EXCLUSIVE, thrown, done);

    assertFalse(done.await(150, TimeUnit.MILLISECONDS), "読まれている間は書き込めない");

    lockManager.releaseAll(tx1);

    assertTrue(done.await(2, TimeUnit.SECONDS), "解放後は取得できる");
    assertNull(thrown.get());
  }

  @Test
  @DisplayName("自分だけが共有ロックを持っている場合は排他ロックへアップグレードできる")
  void upgradeToExclusiveWhenSoleHolder() throws Exception {
    LockManager lockManager = new LockManager(TEST_TIMEOUT_MILLIS);
    Transaction tx1 = new Transaction(1);
    lockManager.acquire(tx1, "users", LockMode.SHARED);

    AtomicReference<Throwable> thrown = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    startAcquire(lockManager, tx1, "users", LockMode.EXCLUSIVE, thrown, done);

    assertTrue(done.await(2, TimeUnit.SECONDS), "単独保持ならアップグレードは待たされない");
    assertNull(thrown.get());
  }

  @Test
  @DisplayName("同じロックの再取得は待たされない")
  void acquiringSameLockTwiceDoesNotBlock() throws Exception {
    LockManager lockManager = new LockManager(TEST_TIMEOUT_MILLIS);
    Transaction tx1 = new Transaction(1);
    lockManager.acquire(tx1, "users", LockMode.SHARED);

    AtomicReference<Throwable> thrown = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    startAcquire(lockManager, tx1, "users", LockMode.SHARED, thrown, done);

    assertTrue(done.await(2, TimeUnit.SECONDS), "再取得は即座に成功する");
    assertNull(thrown.get());
  }

  @Test
  @DisplayName("待機中のトランザクションには先着順でロックが渡される")
  void waitersAreGrantedInFifoOrder() throws Exception {
    LockManager lockManager = new LockManager(TEST_TIMEOUT_MILLIS);
    Transaction tx1 = new Transaction(1);
    Transaction tx2 = new Transaction(2);
    Transaction tx3 = new Transaction(3);
    List<Long> acquiredOrder = Collections.synchronizedList(new ArrayList<>());

    lockManager.acquire(tx1, "users", LockMode.EXCLUSIVE);

    AtomicReference<Throwable> thrown2 = new AtomicReference<>();
    CountDownLatch done2 = new CountDownLatch(1);
    startAcquire(lockManager, tx2, "users", LockMode.EXCLUSIVE, thrown2, done2, acquiredOrder);
    Thread.sleep(100);

    AtomicReference<Throwable> thrown3 = new AtomicReference<>();
    CountDownLatch done3 = new CountDownLatch(1);
    startAcquire(lockManager, tx3, "users", LockMode.EXCLUSIVE, thrown3, done3, acquiredOrder);
    Thread.sleep(100);

    lockManager.releaseAll(tx1);
    assertTrue(done2.await(2, TimeUnit.SECONDS), "先に待っていたtx2が取得する");
    assertEquals(List.of(2L), acquiredOrder, "後から待ったtx3はまだ取得しない");

    lockManager.releaseAll(tx2);
    assertTrue(done3.await(2, TimeUnit.SECONDS), "次に待っていたtx3が取得する");
    assertEquals(List.of(2L, 3L), acquiredOrder);
    assertNull(thrown2.get());
    assertNull(thrown3.get());
  }

  @Test
  @DisplayName("デッドロックはタイムアウトで解消される")
  void deadlockIsResolvedByTimeout() throws Exception {
    LockManager lockManager = new LockManager(TEST_TIMEOUT_MILLIS);
    Transaction tx1 = new Transaction(1);
    Transaction tx2 = new Transaction(2);
    lockManager.acquire(tx1, "a", LockMode.EXCLUSIVE);
    lockManager.acquire(tx2, "b", LockMode.EXCLUSIVE);

    AtomicReference<Throwable> thrown1 = new AtomicReference<>();
    CountDownLatch done1 = new CountDownLatch(1);
    startAcquire(lockManager, tx1, "b", LockMode.EXCLUSIVE, thrown1, done1);
    Thread.sleep(100);

    AtomicReference<Throwable> thrown2 = new AtomicReference<>();
    CountDownLatch done2 = new CountDownLatch(1);
    startAcquire(lockManager, tx2, "a", LockMode.EXCLUSIVE, thrown2, done2);

    assertTrue(done1.await(2, TimeUnit.SECONDS), "tx1が待ち続けずに終了する");
    assertTrue(done2.await(2, TimeUnit.SECONDS), "tx2も待ち続けずに終了する");
    assertInstanceOf(LockTimeoutException.class, thrown1.get());
    assertInstanceOf(LockTimeoutException.class, thrown2.get());
  }

  @Test
  @DisplayName("先頭の待機者がタイムアウトで外れたら、次の待機者がすぐに取得できる")
  void nextWaiterProceedsWhenHeadTimesOut() throws Exception {
    LockManager lockManager = new LockManager(TEST_TIMEOUT_MILLIS);
    Transaction tx1 = new Transaction(1);
    Transaction tx2 = new Transaction(2);
    Transaction tx3 = new Transaction(3);
    lockManager.acquire(tx1, "x", LockMode.SHARED);
    lockManager.acquire(tx2, "x", LockMode.SHARED);

    AtomicReference<Throwable> thrown2 = new AtomicReference<>();
    CountDownLatch done2 = new CountDownLatch(1);
    startAcquire(lockManager, tx2, "x", LockMode.EXCLUSIVE, thrown2, done2);
    Thread.sleep(100);

    AtomicReference<Throwable> thrown3 = new AtomicReference<>();
    CountDownLatch done3 = new CountDownLatch(1);
    startAcquire(lockManager, tx3, "x", LockMode.SHARED, thrown3, done3);

    assertTrue(done2.await(2, TimeUnit.SECONDS), "先頭はタイムアウトになる");
    assertInstanceOf(LockTimeoutException.class, thrown2.get());

    assertTrue(done3.await(1, TimeUnit.SECONDS), "次の待機者は自分のタイムアウトを待たずに取得できる");
    assertNull(thrown3.get());
  }

  private static void startAcquire(
      LockManager lockManager,
      Transaction transaction,
      String tableName,
      LockMode mode,
      AtomicReference<Throwable> thrown,
      CountDownLatch done) {
    startAcquire(lockManager, transaction, tableName, mode, thrown, done, null);
  }

  private static void startAcquire(
      LockManager lockManager,
      Transaction transaction,
      String tableName,
      LockMode mode,
      AtomicReference<Throwable> thrown,
      CountDownLatch done,
      List<Long> acquiredOrder) {
    Thread thread =
        new Thread(
            () -> {
              try {
                lockManager.acquire(transaction, tableName, mode);
                if (acquiredOrder != null) {
                  acquiredOrder.add(transaction.id());
                }
              } catch (Throwable e) {
                thrown.set(e);
              } finally {
                done.countDown();
              }
            });
    thread.setDaemon(true);
    thread.start();
  }
}
