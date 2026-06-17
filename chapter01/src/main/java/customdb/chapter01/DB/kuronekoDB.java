package customdb.chapter01.DB;

import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import customdb.chapter01.Parser.SimpleParser;

/**
 * kuronekoDB クラス
 * シンプルなメモリ内データベースの実装
 * キー・バリューペアでデータを管理し、基本的なCRUD操作を提供する
 */
public class kuronekoDB {
    // データベース本体（キーはID、値は文字列）
    private Map<Integer, String> db;
    // ユーザー入力を受け取るためのスキャナー
    private Scanner scanner;
    // SQL解析用の簡易パーサー
    private SimpleParser parser;

    /**
     * コンストラクタ
     * HashMapとScannerを初期化する
     * DB開始メッセージを表示する
     */
    public kuronekoDB() {
        db = new HashMap<>();
        scanner = new Scanner(System.in);
        System.out.println("Welcome to kuronekoDB!");
    }

    /**
     * 新しいレコードをデータベースに挿入する
     * 同じキーが存在する場合はエラーメッセージを表示
     * キーが数値でない場合は例外を処理してエラーメッセージを表示
     * 
     * @param key   レコードのID（数字）
     * @param value レコードの値（文字列）
     */
    public void insert(String key, String value) {
        try {
            // 文字列をIntegerに変換
            int id = Integer.parseInt(key);
            if (db.containsKey(id)) {
                System.out.println("Key already exists. Use update command to modify.");
                return;
            } else {
                db.put(id, value);
            }
        } catch (NumberFormatException e) {
            // 無効なキー形式の場合はエラーメッセージを表示
            System.out.println("Invalid key format. Please enter a valid integer ID.");
            return;
        }

    }

    /**
     * データベース内のすべてのレコードを表示する
     */
    public void select() {
        db.forEach((id, name) -> System.out.println("(" + id + "," + name + ")"));
    }

    /**
     * 指定されたキーに対応するレコードを検索して表示する
     * キーが存在しない場合はメッセージを表示
     * キーが数値でない場合は例外を処理してエラーメッセージを表示
     * 
     * @param key 検索するレコードのID（数字）
     */
    public void select(String key) {
        try {
            // 文字列をIntegerに変換
            int id = Integer.parseInt(key);
            if (!db.containsKey(id)) {
                System.out.println("Record not found.");
            } else {
                System.out.println(db.get(id));
            }
        } catch (NumberFormatException e) {
            // 無効なキー形式の場合はエラーメッセージを表示
            System.out.println("Invalid key format. Please enter a valid integer ID.");
        }
    }

    /**
     * データベースのメインループを開始する
     * ユーザー入力を受け取り、コマンドを解析して対応する操作を実行する
     * サポートコマンド：
     * - insert <key> <value>: 新規レコードを挿入
     * - select: すべてのレコードを表示
     * - select <key>: 指定されたキーのレコードを表示
     * - exit: プログラムを終了
     * 
     * 無効なコマンドや不正なパラメータ数の場合は、エラーメッセージを表示する
     */
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

            // コマンドが空の場合はスキップ
            if (command.isEmpty()) {
                continue;
            } else if (command.equals("insert") && tokens.length == 3) {
                insert(tokens[1], tokens[2]);
            } else if (command.equals("select")) {
                if (tokens.length == 1)
                    select();
                else if (tokens.length == 2)
                    select(tokens[1]);
            } else if (command.equals("exit")) {
                System.out.println("Bye!");
                break;
            } else {
                System.out.println("Unknown command");
            }
        }
    }

}