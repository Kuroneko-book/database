package customdb.minidb.db;

import customdb.minidb.parser.SimpleParser;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class MiniDB {
    private Map<Integer, String> db;
    private Scanner scanner;
    private SimpleParser parser;

    public MiniDB() {
        db = new HashMap<>();
        scanner = new Scanner(System.in);
        parser = new SimpleParser();
        System.out.println("Welcome to minidb!");
    }

    public void insert(String key, String value) {
        int id;
        try {
            id = Integer.parseInt(key);
        } catch (NumberFormatException e) {
            System.out.println("Error: Key must be an integer.");
            return;
        }

        if (db.putIfAbsent(id, value) != null) {
            System.out.println("Key already exists.");
            return;
        }

        System.out.println("Inserted.");
    }

    public void select() {
        db.forEach((id, name) -> System.out.println("(" + id + "," + name + ")"));
    }

    public void select(String key) {
        int id;
        try {
            id = Integer.parseInt(key);
        } catch (NumberFormatException e) {
            System.out.println("Error: Key must be an integer.");
            return;
        }

        if (!db.containsKey(id)) {
            System.out.println("Record not found.");
        } else {
            System.out.println(db.get(id));
        }
    }

    public void update(String key, String value) {
        int id;
        try {
            id = Integer.parseInt(key);
        } catch (NumberFormatException e) {
            System.out.println("Error: Key must be an integer.");
            return;
        }

        if (!db.containsKey(id)) {
            System.out.println("Record not found.");
            return;
        }

        db.put(id, value);
        System.out.println("Updated.");
    }

    public void delete(String key) {
        int id;
        try {
            id = Integer.parseInt(key);
        } catch (NumberFormatException e) {
            System.out.println("Error: Key must be an integer.");
            return;
        }

        if (!db.containsKey(id)) {
            System.out.println("Record not found.");
            return;
        }

        db.remove(id);
        System.out.println("Deleted.");
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
                System.out.println("Bye!");
                break;
            } else {
                System.out.println("Unknown command");
            }
        }
    }
}
