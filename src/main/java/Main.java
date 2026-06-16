import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Main {

    private static Path currentDirectory = Paths.get("").toAbsolutePath().normalize();

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

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        Set<String> builtins = Set.of(
                "echo",
                "exit",
                "type",
                "pwd",
                "cd"
        );

        while (true) {
            System.out.print("$ ");

            if (!sc.hasNextLine()) {
                break;
            }

            String input = sc.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            String[] parts = input.split("\\s+");
            String command = parts[0];

            // exit
            if (command.equals("exit")) {
                System.exit(0);
            }

            // echo
            if (command.equals("echo")) {
                if (parts.length > 1) {
                    System.out.println(input.substring(5));
                } else {
                    System.out.println();
                }
                continue;
            }

            // pwd
            if (command.equals("pwd")) {
                System.out.println(currentDirectory);
                continue;
            }

            // cd (absolute paths only for this stage)
            if (command.equals("cd")) {
                if (parts.length < 2) {
                    continue;
                }

                Path target = Paths.get(parts[1]);

                if (Files.exists(target) && Files.isDirectory(target)) {
                    currentDirectory = target.toAbsolutePath().normalize();
                } else {
                    System.out.println(
                            "cd: " + parts[1] + ": No such file or directory"
                    );
                }

                continue;
            }

            // type
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

            // external commands
            String executablePath = findExecutable(command);

            if (executablePath != null) {

                List<String> processCommand = new ArrayList<>();
                processCommand.add(executablePath);

                for (int i = 1; i < parts.length; i++) {
                    processCommand.add(parts[i]);
                }

                Process process = new ProcessBuilder(processCommand)
                        .directory(currentDirectory.toFile())
                        .redirectErrorStream(true)
                        .start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {

                    String line;

                    while ((line = reader.readLine()) != null) {
                        System.out.println(line);
                    }
                }

                process.waitFor();

            } else {
                System.out.println(command + ": command not found");
            }
        }
    }
}