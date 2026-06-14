package customdb.chapter01;

import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class Main {
  public static void main(String[] args) {
    // 今回は `insert 1 test` のような形でデータを作成することを想定
    Map<Integer, String> db = new HashMap<>();
    Scanner scanner = new Scanner(System.in);

    while (true) {
      System.out.print("db > ");
      if (!scanner.hasNextLine()) {
        break;
      }

      String[] input = scanner.nextLine().trim().split(" ");
      String command = input[0].toLowerCase();

      if (command.equals("insert")) {
        db.put(Integer.parseInt(input[1]), input[2]);
      } else if (command.equals("select")) {
        db.forEach((id, name) -> System.out.println("(" + id + ", " + name + ")"));
      } else if (command.equals("exit")) {
        System.out.println("Bye!");
        break;
      } else {
        System.out.println("Unrecognized command: " + command);
      }
    }
  }
}
