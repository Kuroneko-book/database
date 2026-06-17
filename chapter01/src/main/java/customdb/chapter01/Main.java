package customdb.chapter01;

import customdb.chapter01.DB.kuronekoDB;

/**
 * Main クラス
 * kuronekoDB データベースアプリケーションのエントリーポイント
 */
public class Main {
  /**
   * メインメソッド
   * データベースのインスタンスを作成し、ユーザーとのインタラクティブなセッションを開始する
   * 
   * @param args コマンドライン引数（使用されていない）
   */
  public static void main(String[] args) {
    // kuronekoDB インスタンスを作成
    kuronekoDB db = new kuronekoDB();
    // データベースのインタラクティブセッションを開始
    db.start();

  }
}
