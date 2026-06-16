import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // TODO: Uncomment the code below to pass the first stage
         while (true) {
            System.out.print("$ ");

            String input = sc.nextLine();

            String command = input.split(" ")[0];

            System.out.println(command + ": command not found");
        }
    }
}
