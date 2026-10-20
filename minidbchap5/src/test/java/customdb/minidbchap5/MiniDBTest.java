package customdb.minidbchap5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import customdb.minidbchap5.db.MiniDB;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MiniDBTest {
  private final PrintStream originalOut = System.out;
  private final InputStream originalIn = System.in;
  private ByteArrayOutputStream outContent;

  @BeforeEach
  void setUp() {
    outContent = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outContent));
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
    System.setIn(originalIn);
  }

  private String getOutput() {
    return outContent.toString();
  }

  private String session(String... lines) {
    return String.join("\n", lines) + "\n";
  }

  private void runRepl(Path dataDir, String input) {
    outContent.reset();
    System.setIn(new ByteArrayInputStream(input.getBytes()));

    try (MiniDB db = new MiniDB(dataDir.toString())) {
      db.start();
    }
  }

  @Nested
  @DisplayName("SQLのテスト")
  class SqlLifecycleTest {
    @Test
    @DisplayName("各コマンドが正常に実行でき、再起動後にデータが保持されている")
    void testSqlLifecycleAndReopen(@TempDir Path dir) {
      runRepl(
          dir,
          session(
              "CREATE TABLE users (id int, name varchar(50), score double);",
              "INSERT INTO users VALUES (1, 'Alice', 3.5);",
              "INSERT INTO users (id, name, score) VALUES (2, 'Bob', 1.25);",
              "SELECT * FROM users;",
              "SELECT name FROM users WHERE id = 2;",
              "UPDATE users SET score = 9.5 WHERE name = 'Alice';",
              "UPDATE users SET id = 10 WHERE name = 'Alice';",
              "SELECT * FROM users WHERE id = 10;",
              "CREATE TABLE orders (id int, item varchar(20), price float);",
              "INSERT INTO orders VALUES (10, 'apple', 100.5);",
              "INSERT INTO orders VALUES (2, 'banana', 200.5);",
              "SELECT users.name, orders.item FROM users JOIN orders ON users.id = orders.id;",
              "DELETE FROM users WHERE id = 10;",
              "SELECT * FROM users;",
              "exit"));

      String output = getOutput();

      assertTrue(output.contains("Table created: users"));
      assertTrue(output.contains("Inserted into users: {id=1, name=Alice, score=3.5}"));
      assertTrue(output.contains("{id=2, name=Bob, score=1.25}"));
      assertTrue(output.contains("{name=Bob}"));
      assertTrue(output.contains("Updated 1 row(s)."));
      assertTrue(output.contains("{id=10, name=Alice, score=9.5}"));
      assertTrue(output.contains("{users.name=Alice, orders.item=apple}"));
      assertTrue(output.contains("{users.name=Bob, orders.item=banana}"));
      assertTrue(output.contains("Deleted 1 row(s)."));
      assertTrue(output.contains("Bye!"));

      // 再起動（close後の同一データディレクトリで新しく起動）
      runRepl(
          dir,
          session(
              "SELECT * FROM users;",
              "SELECT users.name, orders.item FROM users JOIN orders ON users.id = orders.id;",
              "SELECT name FROM users WHERE id = 2;",
              "exit"));

      String reopenOutput = getOutput();
      assertTrue(reopenOutput.contains("{id=2, name=Bob, score=1.25}"));
      assertTrue(reopenOutput.contains("{users.name=Bob, orders.item=banana}"));
      assertTrue(reopenOutput.contains("{name=Bob}"));
    }
  }

  @Nested
  @DisplayName("セミコロンのチェック")
  class SemicolonRuleTest {
    @Test
    @DisplayName("SQLはセミコロン必須 exit/timeは不要")
    void testSemicolonRules(@TempDir Path dir) {
      runRepl(
          dir,
          session(
              "CREATE TABLE t (id int, name varchar(50));",
              "INSERT INTO t VALUES (1, 'a;b');", // クォートで囲まれたセミコロンは文字列として認識
              "SELECT * FROM t;",
              "SELECT * FROM t",
              ";;",
              "time",
              "SELECT * FROM t;",
              "time",
              "DELETE FROM t WHERE id = 1;",
              "SELECT * FROM t;",
              "exit"));

      String output = getOutput();

      assertTrue(output.contains("Inserted into t: {id=1, name=a;b}"));
      assertTrue(output.contains("{id=1, name=a;b}"));
      assertEquals(1, output.split("must end with a semicolon", -1).length - 1);
      assertTrue(output.contains("Unexpected semicolon in SQL statement."));
      assertTrue(output.contains("Timing is on."));
      assertTrue(output.contains("Timing is off."));
      assertTrue(output.contains("Deleted 1 row(s)."));
      assertTrue(output.contains("(empty)"));
      assertTrue(output.contains("Bye!"));
    }
  }

  @Test
  @DisplayName("大文字表名を含むJOINの修飾列を解決する")
  void joinWithUppercaseTableName(@TempDir Path dir) {
    runRepl(
        dir,
        session(
            "CREATE TABLE users (id INT, name VARCHAR(50));",
            "CREATE TABLE orders (id INT, item VARCHAR(20));",
            "INSERT INTO users VALUES (1, 'Alice');",
            "INSERT INTO orders VALUES (1, 'apple');",
            "SELECT Users.name, orders.item FROM Users JOIN orders ON Users.id = orders.id;",
            "exit"));

    assertTrue(getOutput().contains("{Users.name=Alice, orders.item=apple}"));
  }

  @Test
  @DisplayName("既存のデータファイルがある場合はスキップして続行")
  void orphanFileErrorDoesNotEndRepl(@TempDir Path dir) throws IOException {
    Files.createFile(dir.resolve("ghost.db"));
    runRepl(dir, session("CREATE TABLE ghost (id INT);", "CREATE TABLE valid (id INT);", "exit"));

    String output = getOutput();
    assertTrue(output.contains("Error: Data file already exists for new table: ghost.db"));
    assertTrue(output.contains("Table created: valid"));
    assertTrue(output.contains("Bye!"));
  }
}
