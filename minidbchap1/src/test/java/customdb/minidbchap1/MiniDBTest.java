package customdb.minidbchap1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import customdb.minidbchap1.db.MiniDB;
import customdb.minidbchap1.parser.SimpleParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

  @Nested
  @DisplayName("MiniDB 単体操作テスト")
  class CrudOperationsTest {

    @Test
    @DisplayName("insert: 正常系および重複・不正キーのエラーハンドリング")
    void testInsert() {
      MiniDB db = new MiniDB();
      outContent.reset();

      // 正常登録
      db.insert("1", "Alice");
      assertTrue(getOutput().contains("Inserted."));
      outContent.reset();

      // 重複キー登録
      db.insert("1", "Bob");
      assertTrue(getOutput().contains("Key already exists."));
      outContent.reset();

      // 不正キー登録（数値以外）
      db.insert("abc", "Charlie");
      assertTrue(getOutput().contains("Error: Key must be an integer."));
    }

    @Test
    @DisplayName("select: キー指定検索および全件検索")
    void testSelect() {
      MiniDB db = new MiniDB();
      db.insert("10", "Ten");
      db.insert("20", "Twenty");
      outContent.reset();

      // 存在するキー
      db.select("10");
      assertTrue(getOutput().contains("Ten"));
      outContent.reset();

      // 存在しないキー
      db.select("99");
      assertTrue(getOutput().contains("Record not found."));
      outContent.reset();

      // 不正キー
      db.select("invalid");
      assertTrue(getOutput().contains("Error: Key must be an integer."));
      outContent.reset();

      // 全件取得
      db.select();
      String output = getOutput();
      assertTrue(output.contains("(10,Ten)"));
      assertTrue(output.contains("(20,Twenty)"));
    }

    @Test
    @DisplayName("update: 正常更新および存在しないキー・不正キーのエラーハンドリング")
    void testUpdate() {
      MiniDB db = new MiniDB();
      db.insert("1", "OldValue");
      outContent.reset();

      // 正常更新
      db.update("1", "NewValue");
      assertTrue(getOutput().contains("Updated."));
      outContent.reset();

      // 更新結果の確認
      db.select("1");
      assertTrue(getOutput().contains("NewValue"));
      outContent.reset();

      // 存在しないキーの更新
      db.update("999", "NotExist");
      assertTrue(getOutput().contains("Record not found."));
      outContent.reset();

      // 不正キーの更新
      db.update("xyz", "Invalid");
      assertTrue(getOutput().contains("Error: Key must be an integer."));
    }

    @Test
    @DisplayName("delete: 正常削除および存在しないキー・不正キーのエラーハンドリング")
    void testDelete() {
      MiniDB db = new MiniDB();
      db.insert("1", "ToDelete");
      outContent.reset();

      // 正常削除
      db.delete("1");
      assertTrue(getOutput().contains("Deleted."));
      outContent.reset();

      // 削除後の検索（存在しない）
      db.select("1");
      assertTrue(getOutput().contains("Record not found."));
      outContent.reset();

      // 存在しないキーの削除
      db.delete("999");
      assertTrue(getOutput().contains("Record not found."));
      outContent.reset();

      // 不正キーの削除
      db.delete("bad_key");
      assertTrue(getOutput().contains("Error: Key must be an integer."));
    }
  }

  @Nested
  @DisplayName("SimpleParser 単体テスト")
  class ParserTest {

    @Test
    @DisplayName("コマンド解析と小文字化・トークン分割")
    void testParser() {
      SimpleParser parser = new SimpleParser();

      String[] tokens = parser.parse("  INSERT   42   Dave   ");
      assertEquals(3, tokens.length);
      assertEquals("insert", tokens[0]);
      assertEquals("42", tokens[1]);
      assertEquals("Dave", tokens[2]);
      assertEquals("insert", parser.getCommand(tokens));

      String[] singleToken = parser.parse("EXIT");
      assertEquals("exit", parser.getCommand(singleToken));
    }
  }

  @Nested
  @DisplayName("CLI / REPL 統合テスト (start)")
  class ReplIntegrationTest {

    @Test
    @DisplayName("標準入力を介した対話型セッション全体のシナリオテスト")
    void testInteractiveSession() {
      String simulatedInput =
          String.join(
                  "\n",
                  "insert 1 Taro",
                  "insert 2 Jiro",
                  "select 1",
                  "select",
                  "update 1 Saburo",
                  "select 1",
                  "delete 2",
                  "select 2",
                  "invalid_command",
                  "exit")
              + "\n";

      System.setIn(new ByteArrayInputStream(simulatedInput.getBytes()));

      MiniDB db = new MiniDB();
      db.start();

      String output = getOutput();

      // 初期メッセージ
      assertTrue(output.contains("Welcome to minidb!"));
      // insert
      assertTrue(output.contains("Inserted."));
      // select 1
      assertTrue(output.contains("Taro"));
      // select 全件
      assertTrue(output.contains("(1,Taro)"));
      assertTrue(output.contains("(2,Jiro)"));
      // update
      assertTrue(output.contains("Updated."));
      assertTrue(output.contains("Saburo"));
      // delete
      assertTrue(output.contains("Deleted."));
      assertTrue(output.contains("Record not found."));
      // 不明なコマンド
      assertTrue(output.contains("Unknown command"));
      // 終了
      assertTrue(output.contains("Bye!"));
    }
  }
}
