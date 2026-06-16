import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // TODO: Uncomment the code below to pass the first stage
         Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");

            String input = sc.nextLine().trim();

            if (input.equals("exit")) {
                System.exit(0);
            }

            String command = input.split(" ")[0];
            System.out.println(command + ": command not found");
        }
    }
}
