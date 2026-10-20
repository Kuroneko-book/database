package customdb.minidbchap6.db;

import customdb.minidbchap6.executor.QueryExecutor;
import customdb.minidbchap6.parser.SimpleParser;
import customdb.minidbchap6.parser.Statement;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Scanner;

public class MiniDB implements AutoCloseable {
  private static final String DATA_PATH = "data";

  private final Scanner scanner;
  private final SimpleParser parser;
  private final QueryExecutor executor;

  public MiniDB() {
    this(DATA_PATH);
  }

  public MiniDB(String dataPathStr) {
    this.scanner = new Scanner(System.in);
    this.parser = new SimpleParser();
    QueryExecutor created = null;
    try {
      created = new QueryExecutor(Path.of(dataPathStr));
    } catch (IOException e) {
      System.out.println("Failed to initialize database: " + e.getMessage());
    }
    this.executor = created;
    System.out.println("Welcome to minidb!");
  }

  @Override
  public void close() {
    try {
      if (executor != null) {
        executor.close();
      }
    } catch (IOException ignored) {
    }
  }

  public void start() {
    if (executor == null) {
      System.out.println("Database is not initialized.");
      return;
    }
    boolean showTime = false;

    while (true) {
      System.out.print("minidb > ");
      System.out.flush();
      if (!scanner.hasNextLine()) {
        break;
      }

      String line = scanner.nextLine();

      long startTime = System.nanoTime();

      if (line.isEmpty()) {
        continue;
      } else if (line.equals("time")) {
        showTime = !showTime;
        System.out.println("Timing is " + (showTime ? "on." : "off."));
        continue;
      } else if (line.equals("exit")) {
        close();
        System.out.println("Bye!");
        break;
      }

      try {
        Statement statement = parser.parseStatement(line);
        executor.execute(statement);
      } catch (RuntimeException e) {
        System.out.println("Error: " + e.getMessage());
      } catch (IOException e) {
        System.out.println("Disk I/O Error during command.");
      }

      if (showTime) {
        long endTime = System.nanoTime();
        double timeMs = (endTime - startTime) / 1_000_000.0;
        System.out.printf("(Executed in %.3f ms)\n", timeMs);
      }
    }
  }
}
