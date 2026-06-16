import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // TODO: Uncomment the code below to pass the first stage
         Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");

            String input = sc.nextLine().trim();

            String[] parts = input.split("\\s+");
            String command = parts[0];

            if (command.equals("exit")) {
                System.exit(0);
            }

            if (command.equals("echo")) {
                if (parts.length > 1) {
                    System.out.println(input.substring(5));
                } else {
                    System.out.println();
                }
                continue;
            }

            System.out.println(command + ": command not found");
        }
    }
}
