package customdb.minidb.parser;

public class SimpleParser {
    public SimpleParser() {}

    public String[] parse(String sql) {
        String[] tokens = sql.trim().split("\\s+");
        tokens[0] = tokens[0].toLowerCase();
        return tokens;
    }

    public String getCommand(String[] tokens) {
        if (tokens.length > 0) {
            return tokens[0];
        }
        return "";
    }

    public Integer parseKey(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            System.out.println("Error: Key must be an integer.");
            return null;
        }
    }
}
