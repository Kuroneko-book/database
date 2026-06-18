package customdb.chapter02.Parser;

public class SimpleParser {

  public SimpleParser() {}

  /**
   * SQLコマンド文字列をトークンに分割する 空白で区切られたトークンに分割し、最初のトークンを小文字に変換する
   *
   * @param sql パースするSQLコマンド文字列
   * @return 分割されたトークンの配列
   */
  public String[] parse(String sql) {
    String[] tokens = sql.trim().split("\\s+");
    tokens[0] = tokens[0].toLowerCase();
    return tokens;
  }

  /**
   * トークン配列から最初のトークン（コマンド）を取得する トークン配列が空の場合は空文字列を返す
   *
   * @param tokens パース済みのトークン配列
   * @return 最初のトークン、またはトークンが無い場合は空文字列
   */
  public String getCommand(String[] tokens) {
    return tokens.length > 0 ? tokens[0] : "";
  }
}
