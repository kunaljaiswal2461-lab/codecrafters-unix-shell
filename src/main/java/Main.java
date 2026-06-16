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

    private static Path currentDirectory =
            Paths.get("").toAbsolutePath().normalize();

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

    private static List<String> parseCommand(String input) {
        List<String> tokens = new ArrayList<>();

        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (ch == '\'') {
                inSingleQuotes = !inSingleQuotes;
                continue;
            }

            if (Character.isWhitespace(ch) && !inSingleQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
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

            String input = sc.nextLine();

            if (input.trim().isEmpty()) {
                continue;
            }

            List<String> parts = parseCommand(input);

            if (parts.isEmpty()) {
                continue;
            }

            String command = parts.get(0);

            // exit
            if (command.equals("exit")) {
                System.exit(0);
            }

            // echo
            if (command.equals("echo")) {

                for (int i = 1; i < parts.size(); i++) {
                    if (i > 1) {
                        System.out.print(" ");
                    }
                    System.out.print(parts.get(i));
                }

                System.out.println();
                continue;
            }

            // pwd
            if (command.equals("pwd")) {
                System.out.println(currentDirectory);
                continue;
            }

            // cd
            if (command.equals("cd")) {

                if (parts.size() < 2) {
                    continue;
                }

                String targetDir = parts.get(1);
                Path targetPath;

                if (targetDir.equals("~")) {
                    String home = System.getenv("HOME");

                    if (home != null) {
                        currentDirectory = Paths.get(home)
                                .toAbsolutePath()
                                .normalize();
                    }

                    continue;
                }

                if (Paths.get(targetDir).isAbsolute()) {
                    targetPath = Paths.get(targetDir);
                } else {
                    targetPath = currentDirectory.resolve(targetDir);
                }

                targetPath = targetPath.normalize();

                if (Files.exists(targetPath) &&
                        Files.isDirectory(targetPath)) {

                    currentDirectory = targetPath;

                } else {

                    System.out.println(
                            "cd: " + targetDir +
                                    ": No such file or directory"
                    );
                }

                continue;
            }

            // type
            if (command.equals("type")) {

                if (parts.size() < 2) {
                    continue;
                }

                String target = parts.get(1);

                if (builtins.contains(target)) {

                    System.out.println(
                            target + " is a shell builtin"
                    );

                } else {

                    String executable =
                            findExecutable(target);

                    if (executable != null) {

                        System.out.println(
                                target + " is " + executable
                        );

                    } else {

                        System.out.println(
                                target + ": not found"
                        );
                    }
                }

                continue;
            }

            // external command
            String executable = findExecutable(command);

            if (executable != null) {

                Process process = new ProcessBuilder(parts)
                        .directory(currentDirectory.toFile())
                        .redirectErrorStream(true)
                        .start();

                try (BufferedReader reader =
                             new BufferedReader(
                                     new InputStreamReader(
                                             process.getInputStream()
                                     )
                             )) {

                    String line;

                    while ((line = reader.readLine()) != null) {
                        System.out.println(line);
                    }
                }

                process.waitFor();

            } else {

                System.out.println(
                        command + ": command not found"
                );
            }
        }
    }
}