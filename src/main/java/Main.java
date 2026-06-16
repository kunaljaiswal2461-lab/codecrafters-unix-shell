import java.io.File;
import java.util.Scanner;
import java.util.Set;

public class Main {

    private static String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");

        if (pathEnv == null) {
            return null;
        }

        String[] directories = pathEnv.split(File.pathSeparator);

        for (String dir : directories) {
            File file = new File(dir, command);

            if (file.exists() && file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }

    public static void main(String[] args) {
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
                if (parts.length < 2) {
                    continue;
                }

                String target = parts[1];

                if (builtins.contains(target)) {
                    System.out.println(target + " is a shell builtin");
                } else {
                    String executablePath = findExecutable(target);

                    if (executablePath != null) {
                        System.out.println(target + " is " + executablePath);
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