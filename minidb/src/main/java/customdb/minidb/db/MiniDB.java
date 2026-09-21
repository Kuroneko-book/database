package customdb.minidb.db;

import customdb.minidb.parser.SimpleParser;
import customdb.minidb.storage.PageManager;
import customdb.minidb.storage.Record;
import java.io.IOException;
import java.util.List;
import java.util.Scanner;

public class MiniDB implements AutoCloseable {
  private static final String DATA_PATH = "data/mini.db";

  private final Scanner scanner;
  private final SimpleParser parser;
  private PageManager pageManager;

  public MiniDB() {
    this(DATA_PATH);
  }

  public MiniDB(String dataPathStr) {
    this.scanner = new Scanner(System.in);
    this.parser = new SimpleParser();

    try {
      this.pageManager = new PageManager(dataPathStr);
    } catch (IOException e) {
      System.out.println("Failed to initialize database: " + e.getMessage());
    }
    System.out.println("Welcome to minidb!");
  }

  public void insert(String key, String value) {
    try {
      if (pageManager.findRecord(key) != null) {
        System.out.println("Key already exists. Use update command to modify.");
        return;
      }

      pageManager.insertRecord(key, value);
      System.out.println("Inserted and saved to disk.");

    } catch (IllegalArgumentException e) {
      System.out.println("Insert Error: " + e.getMessage());
    } catch (IOException e) {
      System.out.println("Disk I/O Error during insert.");
    }
  }

  public void select() {
    try {
      List<Record> records = pageManager.findAllRecords();
      for (Record record : records) {
        System.out.println("(" + record.key() + ", " + record.value() + ")");
      }
    } catch (IOException e) {
      System.out.println("Disk I/O Error during select.");
    }
  }

  public void select(String key) {
    try {
      Record record = pageManager.findRecord(key);
      if (record != null) {
        System.out.println(record.value());
      } else {
        System.out.println("Record not found.");
      }
    } catch (IOException e) {
      System.out.println("Disk I/O Error during select.");
    }
  }

  public void update(String key, String value) {
    try {
      boolean updated = pageManager.updateRecordByKey(key, value);
      if (updated) {
        System.out.println("Updated and saved to disk.");
      } else {
        System.out.println("Record not found.");
      }
    } catch (IllegalArgumentException e) {
      System.out.println("Update Error: " + e.getMessage());
    } catch (IOException e) {
      System.out.println("Disk I/O Error during update.");
    }
  }

  public void delete(String key) {
    try {
      boolean deleted = pageManager.deleteRecordByKey(key);
      if (deleted) {
        System.out.println("Deleted and saved to disk.");
      } else {
        System.out.println("Record not found.");
      }
    } catch (IOException e) {
      System.out.println("Disk I/O Error during delete.");
    }
  }

  @Override
  public void close() {
    try {
      if (pageManager != null) {
        pageManager.close();
      }
    } catch (IOException ignored) {
    }
  }

  public void start() {
    if (pageManager == null) {
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

      String[] tokens = parser.parse(scanner.nextLine());
      String command = parser.getCommand(tokens);

      long startTime = System.nanoTime();

      if (command.isEmpty()) {
        continue;
      } else if (command.equals("time")) {
        showTime = !showTime;
        System.out.println("Timing is " + (showTime ? "on." : "off."));
        continue;
      } else if (command.equals("insert") && tokens.length == 3) {
        insert(tokens[1], tokens[2]);
      } else if (command.equals("select")) {
        if (tokens.length == 1) select();
        else if (tokens.length == 2) select(tokens[1]);
        else System.out.println("Unknown command");
      } else if (command.equals("update") && tokens.length == 3) {
        update(tokens[1], tokens[2]);
      } else if (command.equals("delete") && tokens.length == 2) {
        delete(tokens[1]);
      } else if (command.equals("exit")) {
        close();
        System.out.println("Bye!");
        break;
      } else {
        System.out.println("Unknown command");
      }

      if (showTime) {
        long endTime = System.nanoTime();
        double timeMs = (endTime - startTime) / 1_000_000.0;
        System.out.printf("(Executed in %.3f ms)\n", timeMs);
      }
    }
  }
}
