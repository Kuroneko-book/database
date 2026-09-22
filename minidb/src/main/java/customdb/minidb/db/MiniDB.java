package customdb.minidb.db;

import customdb.minidb.parser.SimpleParser;
import customdb.minidb.parser.Statement;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Scanner;

public class MiniDB implements AutoCloseable {
  private static final String DATA_PATH = "data/minidb";

  private final Scanner scanner;
  private final SimpleParser parser;
  private QueryExecutor executor;

  public MiniDB() {
    this(DATA_PATH);
  }

  public MiniDB(String dataPathStr) {
    this.scanner = new Scanner(System.in);
    this.parser = new SimpleParser();

    try {
      this.executor = new QueryExecutor(Path.of(dataPathStr));
    } catch (IOException e) {
      System.out.println("Failed to initialize database: " + e.getMessage());
    }
    System.out.println("Welcome to minidb!");
  }

  @Override
  public void close() {
    try {
      if (executor != null) {
        executor.close();
      }
    } catch (IOException e) {
      System.out.println("Error while closing database: " + e.getMessage());
    }
  }

  public void start() {
    if (executor == null) {
      System.out.println("Database is not initialized.");
      return;
    }
    boolean showTime = false;

    try {
      while (true) {
        System.out.print("minidb > ");
        System.out.flush();
        if (!scanner.hasNextLine()) {
          break;
        }

        String sql = scanner.nextLine().trim();
        if (sql.isEmpty()) {
          continue;
        } else if (sql.equalsIgnoreCase("time")) {
          showTime = !showTime;
          System.out.println("Timing is " + (showTime ? "on." : "off."));
          continue;
        } else if (sql.equalsIgnoreCase("exit")) {
          System.out.println("Bye!");
          break;
        }

        long startTime = System.nanoTime();
        try {
          Statement statement = parser.parseStatement(sql);
          executor.execute(statement);
        } catch (IllegalArgumentException | IOException e) {
          System.out.println("Error: " + e.getMessage());
        }

        if (showTime) {
          long endTime = System.nanoTime();
          double timeMs = (endTime - startTime) / 1_000_000.0;
          System.out.printf("(Executed in %.3f ms)\n", timeMs);
        }
      }
    } finally {
      close();
    }
  }
}
