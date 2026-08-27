package customdb.minidb.db;

import customdb.minidb.parser.SimpleParser;
import customdb.minidb.storage.Page;
import customdb.minidb.storage.Record;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

public class MiniDB implements AutoCloseable {
    private static final String DATA_PATH = "data/mini.db";

    private final Scanner scanner;
    private final SimpleParser parser;
    private final Path dataPath;

    private RandomAccessFile file;

    public MiniDB() {
        this(DATA_PATH);
    }

    public MiniDB(String dataPathStr) {
        this.scanner = new Scanner(System.in);
        this.parser = new SimpleParser();
        this.dataPath = Path.of(dataPathStr);

        try {
            if (dataPath.getParent() != null && !Files.exists(dataPath.getParent())) {
                Files.createDirectories(dataPath.getParent());
            }
            this.file = new RandomAccessFile(dataPath.toFile(), "rw");
        } catch (IOException e) {
            System.out.println("Failed to initialize database: " + e.getMessage());
            return;
        }
        System.out.println("Welcome to minidb!");
    }

    private int getPageCount() throws IOException {
        return (int) Math.ceil((double) file.length() / Page.PAGE_SIZE);
    }

    private Page readPage(int pageNo) throws IOException {
        byte[] buf = new byte[Page.PAGE_SIZE];
        file.seek((long) pageNo * Page.PAGE_SIZE);
        file.read(buf);
        return new Page(buf);
    }

    private void writePage(int pageNo, Page page) throws IOException {
        file.seek((long) pageNo * Page.PAGE_SIZE);
        file.write(page.getData());
    }

    public void insert(String key, String value) {
        Integer id = parser.parseKey(key);
        if (id == null)
            return;

        try {
            int numPages = getPageCount();
            int targetPage = -1;
            int targetSlot = -1;

            for (int p = 0; p < numPages; p++) {
                Page page = readPage(p);
                for (int s = 0; s < Page.MAX_SLOTS; s++) {
                    if (page.isSlotUsed(s)) {
                        if (page.getKey(s) == id) {
                            System.out.println("Key already exists. Use update command to modify.");
                            return;
                        }
                    } else if (targetPage == -1) {
                        targetPage = p;
                        targetSlot = s;
                    }
                }
            }

            if (targetPage == -1) {
                targetPage = numPages;
                targetSlot = 0;
            }

            Page page;
            if (targetPage < numPages) {
                page = readPage(targetPage);
            } else {
                page = new Page();
            }
            page.writeRecord(targetSlot, id, value);
            writePage(targetPage, page);

            System.out.println("Inserted and saved to disk.");
        } catch (IOException e) {
            System.out.println("Disk I/O Error during insert.");
        }
    }

    public void select() {
        try {
            int numPages = getPageCount();
            for (int p = 0; p < numPages; p++) {
                Page page = readPage(p);
                for (int s = 0; s < Page.MAX_SLOTS; s++) {
                    Record record = page.getRecord(s);
                    if (record != null) {
                        System.out.println("(" + record.key() + "," + record.value() + ")");
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("Disk I/O Error during select.");
        }
    }

    public void select(String key) {
        Integer id = parser.parseKey(key);
        if (id == null)
            return;

        try {
            int numPages = getPageCount();
            for (int p = 0; p < numPages; p++) {
                Page page = readPage(p);
                for (int s = 0; s < Page.MAX_SLOTS; s++) {
                    if (page.isSlotUsed(s) && page.getKey(s) == id) {
                        Record record = page.getRecord(s);
                        if (record != null) {
                            System.out.println(record.value());
                        }
                        return;
                    }
                }
            }
            System.out.println("Record not found.");
        } catch (IOException e) {
            System.out.println("Disk I/O Error during select.");
        }
    }

    public void update(String key, String value) {
        Integer id = parser.parseKey(key);
        if (id == null)
            return;

        try {
            int numPages = getPageCount();
            for (int p = 0; p < numPages; p++) {
                Page page = readPage(p);
                for (int s = 0; s < Page.MAX_SLOTS; s++) {
                    if (page.isSlotUsed(s) && page.getKey(s) == id) {
                        page.updateValue(s, value);
                        writePage(p, page);
                        System.out.println("Updated correctly");
                        return;
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("Disk I/O Error during update.");
        }
    }

    public void delete(String key) {
        Integer id = parser.parseKey(key);
        if (id == null)
            return;

        try {
            int numPages = getPageCount();
            for (int p = 0; p < numPages; p++) {
                Page page = readPage(p);
                for (int s = 0; s < Page.MAX_SLOTS; s++) {
                    if (page.isSlotUsed(s) && page.getKey(s) == id) {
                        page.deleteRecord(s);
                        writePage(p, page);
                        System.out.println("Deleted and saved to disk.");
                        return;
                    }
                }
            }
            System.out.println("Record not found.");
        } catch (IOException e) {
            System.out.println("Disk I/O Error during delete.");
        }
    }

    @Override
    public void close() {
        try {
            if (file != null) {
                file.close();
            }
        } catch (IOException ignored) {
        }
    }

    public void start() {
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
            } else if (command.equals("insert") && tokens.length == 3) {
                insert(tokens[1], tokens[2]);
            } else if (command.equals("select")) {
                if (tokens.length == 1)
                    select();
                else if (tokens.length == 2)
                    select(tokens[1]);
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

            long endTime = System.nanoTime();
            double timeMs = (endTime - startTime) / 1_000_000.0;
            System.out.printf("(Executed in %.3f ms)\n", timeMs);
        }
    }
}
