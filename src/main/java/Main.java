import java.util.Scanner;
import java.util.Set;

public class Main {
    public static void main(String[] args) throws Exception {
        // TODO: Uncomment the code below to pass the first stage
         Scanner sc = new Scanner(System.in);

        Set<String> builtins = Set.of("echo", "exit", "type");

        while (true) {
            System.out.print("$ ");

            String input = sc.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            String[] parts = input.split("\\s+");
            String command = parts[0];

            // exit builtin
            if (command.equals("exit")) {
                System.exit(0);
            }

            // echo builtin
            if (command.equals("echo")) {
                if (parts.length > 1) {
                    System.out.println(input.substring(5));
                } else {
                    System.out.println();
                }
                continue;
            }

            // type builtin
            if (command.equals("type")) {
                if (parts.length > 1) {
                    String target = parts[1];

                    if (builtins.contains(target)) {
                        System.out.println(target + " is a shell builtin");
                    } else {
                        System.out.println(target + ": not found");
                    }
                }
                continue;
            }

            // unknown command
            System.out.println(command + ": command not found");
        }
    }
}
