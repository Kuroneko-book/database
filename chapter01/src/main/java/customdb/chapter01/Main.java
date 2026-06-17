package customdb.chapter01;

import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Map<Integer, String> db = new HashMap<>();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("db > ");
            if (!scanner.hasNextLine()) {
                break;
            }

            String[] input = scanner.nextLine().trim().split(" ");
            String command = input[0].toLowerCase();

            if (command.isEmpty()) {
                continue;
            } else if (command.equals("insert")) {
                if (input.length < 3) {
                    System.out.println("Usage: insert <id> <name>");
                } else {
                    int id = Integer.parseInt(input[1]);
                    if (db.containsKey(id)) {
                        System.out.println("Error: Duplicate key " + id);
                    } else {
                        db.put(id, input[2]);
                    }
                }
            } else if (command.equals("select")) {
                if (input.length == 1) {
                    db.forEach((id, name) -> System.out.println("(" + id + ", " + name + ")"));
                } else {
                    int id = Integer.parseInt(input[1]);
                    if (db.containsKey(id)) {
                        System.out.println("(" + id + ", " + db.get(id) + ")");
                    } else {
                        System.out.println("Record not found.");
                    }
                }
            } else if (command.equals("exit")) {
                System.out.println("Bye!");
                break;
            } else {
                System.out.println("Unrecognized command: " + command);
            }
        }
    }
}
