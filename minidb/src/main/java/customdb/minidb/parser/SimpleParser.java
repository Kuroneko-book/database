package customdb.minidb.parser;

public class SimpleParser {
  public String[] parse(String sql) {
    if (sql == null || sql.trim().isEmpty()) {
      return new String[0];
    }
    String[] tokens = sql.trim().split("\\s+");
    tokens[0] = tokens[0].toLowerCase();
    return tokens;
  }

  public String getCommand(String[] tokens) {
    if (tokens != null && tokens.length > 0) {
      return tokens[0];
    }
    return "";
  }
}
