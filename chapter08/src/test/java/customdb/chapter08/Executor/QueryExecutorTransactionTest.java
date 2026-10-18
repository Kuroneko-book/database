package customdb.chapter08.Executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import customdb.chapter08.Parser.SimpleParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Begin/Commit/Abortによる変更の確定と取り消しを、SQLを実行して確認する
class QueryExecutorTransactionTest {
  @TempDir Path tempDir;

  private final SimpleParser parser = new SimpleParser();
  private QueryExecutor executor;

  @BeforeEach
  void setUp() throws IOException {
    executor = new QueryExecutor(tempDir);
  }

  @AfterEach
  void tearDown() throws IOException {
    executor.close();
  }

  @Test
  @DisplayName("Commitするとトランザクション内の変更が確定する")
  void commitKeepsChanges() throws IOException {
    execute("create table users (id int, name varchar(30))");
    execute("begin");
    execute("insert into users values (1, 'alice')");
    execute("commit");

    assertTrue(execute("select * from users").contains("name=alice"));
  }

  @Test
  @DisplayName("Abortすると挿入・更新・削除がすべて取り消される")
  void abortUndoesAllChanges() throws IOException {
    execute("create table users (id int, name varchar(30))");
    execute("insert into users values (1, 'alice')");
    execute("begin");
    execute("insert into users values (2, 'bob')");
    execute("update users set name = 'BOB' where id = 2");
    execute("delete from users where id = 1");

    String during = execute("select * from users");
    assertTrue(during.contains("name=BOB"), "トランザクション内では変更が見える");
    assertFalse(during.contains("alice"));

    execute("abort");

    String after = execute("select * from users");
    assertTrue(after.contains("name=alice"), "削除が取り消されてaliceが戻る");
    assertFalse(after.contains("bob"), "挿入と更新が取り消される");
  }

  @Test
  @DisplayName("文の途中でエラーが起きるとトランザクションは自動的にアボートされる")
  void statementErrorAbortsActiveTransaction() throws IOException {
    execute("create table users (id int, name varchar(30))");
    execute("begin");
    execute("insert into users values (3, 'carol')");

    assertThrows(RuntimeException.class, () -> execute("insert into users values (4)"));

    assertFalse(execute("select * from users").contains("carol"), "エラー前の挿入も取り消される");
    assertThrows(IllegalStateException.class, () -> execute("commit"), "トランザクションはすでにない");
  }

  @Test
  @DisplayName("Abortするとトランザクション外の変更は残る")
  void abortKeepsAutoCommitChanges() throws IOException {
    execute("create table users (id int, name varchar(30))");
    execute("insert into users values (5, 'erin')");
    execute("begin");
    execute("insert into users values (6, 'frank')");
    execute("abort");

    String after = execute("select * from users");
    assertTrue(after.contains("name=erin"), "自動コミット済みの変更は残る");
    assertFalse(after.contains("frank"));
  }

  @Test
  @DisplayName("Commitした変更はデータベースを開き直しても残る")
  void commitSurvivesReopen() throws IOException {
    execute("create table users (id int, name varchar(30))");
    execute("begin");
    execute("insert into users values (7, 'dave')");
    execute("commit");

    executor.close();
    executor = new QueryExecutor(tempDir);

    assertTrue(execute("select * from users").contains("name=dave"));
  }

  @Test
  @DisplayName("Abortした変更はデータベースを開き直すと残っていない")
  void abortDoesNotSurviveReopen() throws IOException {
    execute("create table users (id int, name varchar(30))");
    execute("begin");
    execute("insert into users values (8, 'eve')");
    execute("abort");

    executor.close();
    executor = new QueryExecutor(tempDir);

    assertFalse(execute("select * from users").contains("name=eve"));
  }

  @Test
  @DisplayName("トランザクション内ではCREATE TABLEできない")
  void createTableInsideTransactionIsRejected() throws IOException {
    execute("begin");

    assertThrows(IllegalStateException.class, () -> execute("create table t (id int)"));

    execute("abort");
  }

  @Test
  @DisplayName("トランザクション内で再度beginするとエラーになる")
  void beginTwiceIsRejected() throws IOException {
    execute("begin");

    assertThrows(IllegalStateException.class, () -> execute("begin"));

    execute("abort");
  }

  // 実行結果（selectの行など）を確認できるように、標準出力を文字列として受け取る
  private String execute(String sql) throws IOException {
    PrintStream original = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    try {
      executor.execute(parser.parseStatement(sql));
    } finally {
      System.setOut(original);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }
}
