package customdb.chapter02.DB;

import customdb.chapter02.Parser.SimpleParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/** nekoDB クラス シンプルなメモリ内データベースの実装 キー・バリューペアでデータを管理し、基本的なCRUD操作を提供する */
public class nekoDB {
  private static final String DATA_PATH = "data/chapter02.db";
  private Map<Integer, String> db;
  private Scanner scanner;
  private SimpleParser parser;
  private Path dataPath;

  public nekoDB() {
    db = new HashMap<>();
    scanner = new Scanner(System.in);
    dataPath = Path.of(DATA_PATH);
    loadFromFile();
    System.out.println("Welcome to nekoDB!");
  }

  private void loadFromFile() {
    if (!Files.exists(dataPath)) {
      return;
    }

    try {
      List<String> lines = Files.readAllLines(dataPath);
      for (String line : lines) {
        if (line.isBlank()) {
          continue;
        }
        String[] parts = line.split(",");
        db.put(Integer.parseInt(parts[0]), parts[1]);
      }
    } catch (IOException e) {
      System.out.println("Failed to load database.");
    }
  }

  /** すべてのレコードを key,value 形式のテキストでファイルに保存する */
  private void saveToFile() {
    try {
      Path path = dataPath.getParent();
      if (path != null) {
        Files.createDirectories(path);
      }

      List<String> lines =
          db.entrySet().stream().map(entry -> entry.getKey() + "," + entry.getValue()).toList();
      Files.write(dataPath, lines);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to save database to ");
    }
  }

  /**
   * 新しいレコードをデータベースに挿入する
   *
   * @param key レコードのID（数字）
   * @param value レコードの値（文字列）
   */
  public void insert(String key, String value) {
    int id = Integer.parseInt(key);
    if (db.containsKey(id)) {
      System.out.println("Key already exists. Use update command to modify.");
      return;
    } else {
      db.put(id, value);
      saveToFile();
    }
  }

  /** データベース内のすべてのレコードを表示する */
  public void select() {
    db.forEach((id, name) -> System.out.println("(" + id + "," + name + ")"));
  }

  /**
   * 指定されたキーに対応するレコードを検索して表示する
   *
   * @param key 検索するレコードのID（数字）
   */
  public void select(String key) {
    int id = Integer.parseInt(key);
    if (!db.containsKey(id)) {
      System.out.println("Record not found.");
    } else {
      System.out.println(db.get(id));
    }
  }

  public void start() {
    while (true) {
      System.out.print("db > ");
      System.out.flush();
      if (!scanner.hasNextLine()) {
        break;
      }

      parser = new SimpleParser();
      String[] tokens = parser.parse(scanner.nextLine());
      String command = parser.getCommand(tokens);

      if (command.isEmpty()) {
        continue;
      } else if (command.equals("insert") && tokens.length == 3) {
        insert(tokens[1], tokens[2]);
      } else if (command.equals("select")) {
        if (tokens.length == 1) select();
        else if (tokens.length == 2) select(tokens[1]);
      } else if (command.equals("exit")) {
        System.out.println("Bye!");
        break;
      } else {
        System.out.println("Unknown command");
      }
    }
  }
}
