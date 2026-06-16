import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {
    private static final Set<String> BUILTINS = Set.of("echo", "exit", "type", "pwd", "cd", "jobs");
    private static final Map<Integer, Job> JOBS = new HashMap<>();
    private static Path currentDirectory = Paths.get("").toAbsolutePath().normalize();
    private static boolean skipLeadingLineFeed = false;
    private static String lastCompletionInput = null;

    private record Redirection(int fd, Path target, boolean append) {
    }

    private static class Command {
        private final List<String> args = new ArrayList<>();
        private final List<Redirection> redirections = new ArrayList<>();

        private boolean isBuiltin() {
            return !args.isEmpty() && BUILTINS.contains(args.get(0));
        }
    }

    private record ParsedLine(List<Command> commands, boolean background, String originalCommand) {
    }

    private static class Job {
        private final int number;
        private final String commandLine;
        private final List<Process> processes;
        private final AtomicBoolean done = new AtomicBoolean(false);

        private Job(int number, String commandLine, List<Process> processes) {
            this.number = number;
            this.commandLine = commandLine;
            this.processes = processes;
        }

        private long pid() {
            return processes.isEmpty() ? -1 : processes.get(processes.size() - 1).pid();
        }
    }

    public static void main(String[] args) throws Exception {
        TerminalMode terminalMode = enableRawMode();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> restoreTerminal(terminalMode)));

        try {
            while (true) {
                reapCompletedJobs();
                System.out.print("$ ");
                System.out.flush();

                String input = readInputLine();
                if (input == null) {
                    break;
                }

                if (input.trim().isEmpty()) {
                    continue;
                }

                try {
                    ParsedLine parsed = parseLine(input);
                    if (parsed.commands().isEmpty()) {
                        continue;
                    }
                    execute(parsed);
                } catch (ShellParseException e) {
                    System.err.println(e.getMessage());
                }
            }
        } finally {
            restoreTerminal(terminalMode);
        }
    }

    private static String readInputLine() throws IOException {
        InputStream input = System.in;
        StringBuilder line = new StringBuilder();

        while (true) {
            int value = input.read();
            if (value == -1) {
                return line.isEmpty() ? null : line.toString();
            }

            char ch = (char) value;
            if (line.isEmpty() && ch == '\n' && skipLeadingLineFeed) {
                skipLeadingLineFeed = false;
                continue;
            }

            if (ch == '\n' || ch == '\r') {
                skipLeadingLineFeed = ch == '\r';
                lastCompletionInput = null;
                System.out.println();
                return line.toString();
            }

            if (ch == '\t') {
                completeInput(line);
                continue;
            }

            lastCompletionInput = null;
            if (ch == 127 || ch == '\b') {
                if (!line.isEmpty()) {
                    line.deleteCharAt(line.length() - 1);
                    System.out.print("\b \b");
                    System.out.flush();
                }
                continue;
            }

            line.append(ch);
            System.out.print(ch);
            System.out.flush();
        }
    }

    private static void completeInput(StringBuilder line) {
        String current = line.toString();
        int tokenStart = currentTokenStart(current);
        String prefix = current.substring(tokenStart);
        List<String> matches = tokenStart == 0
                ? commandCompletionCandidates(prefix)
                : filenameCompletionCandidates(prefix);

        applyCompletion(line, prefix, matches);
    }

    private static int currentTokenStart(String line) {
        int index = line.length() - 1;
        while (index >= 0 && !Character.isWhitespace(line.charAt(index))) {
            index--;
        }
        return index + 1;
    }

    private static List<String> commandCompletionCandidates(String prefix) {
        return completionCandidates().stream()
                .filter(candidate -> candidate.startsWith(prefix))
                .sorted()
                .distinct()
                .toList();
    }

    private static List<String> filenameCompletionCandidates(String prefix) {
        int slashIndex = prefix.lastIndexOf('/');
        String directoryPart = slashIndex == -1 ? "" : prefix.substring(0, slashIndex + 1);
        String filePrefix = slashIndex == -1 ? prefix : prefix.substring(slashIndex + 1);
        Path directory = directoryPart.isEmpty() ? currentDirectory : resolvePath(directoryPart);
        String renderedPrefix = directoryPart;

        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        try (var stream = Files.list(directory)) {
            return stream
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(filePrefix))
                    .map(name -> {
                        Path fullPath = directory.resolve(name);
                        String suffix = Files.isDirectory(fullPath) ? "/" : "";
                        return renderedPrefix + name + suffix;
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void applyCompletion(StringBuilder line, String prefix, List<String> matches) {
        String current = line.toString();

        if (matches.size() == 1) {
            String match = matches.get(0);
            String completion = match.substring(prefix.length()) + (match.endsWith("/") ? "" : " ");
            line.append(completion);
            System.out.print(completion);
            lastCompletionInput = null;
        } else if (matches.isEmpty()) {
            System.out.print("\u0007");
            lastCompletionInput = null;
        } else {
            String commonPrefix = commonPrefix(matches);
            if (commonPrefix.length() > prefix.length()) {
                String completion = commonPrefix.substring(prefix.length());
                line.append(completion);
                System.out.print(completion);
                lastCompletionInput = null;
            } else if (current.equals(lastCompletionInput)) {
                System.out.println();
                System.out.println(String.join("  ", matches));
                System.out.print("$ " + current);
                lastCompletionInput = null;
            } else {
                System.out.print("\u0007");
                lastCompletionInput = current;
            }
        }
        System.out.flush();
    }

    private static List<String> completionCandidates() {
        List<String> candidates = new ArrayList<>(BUILTINS);
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return candidates;
        }

        for (String dir : pathEnv.split(File.pathSeparator)) {
            File directory = new File(dir);
            File[] files = directory.listFiles();
            if (files == null) {
                continue;
            }

            for (File file : files) {
                if (file.isFile() && file.canExecute()) {
                    candidates.add(file.getName());
                }
            }
        }
        return candidates;
    }

    private static String commonPrefix(List<String> values) {
        if (values.isEmpty()) {
            return "";
        }

        String prefix = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            String value = values.get(i);
            int end = 0;
            while (end < prefix.length() && end < value.length() && prefix.charAt(end) == value.charAt(end)) {
                end++;
            }
            prefix = prefix.substring(0, end);
        }
        return prefix;
    }

    private record TerminalMode(boolean changed, String settings) {
    }

    private static TerminalMode enableRawMode() {
        try {
            Process readSettings = new ProcessBuilder("sh", "-c", "stty -g < /dev/tty")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String settings = new String(readSettings.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (readSettings.waitFor() != 0 || settings.isEmpty()) {
                return new TerminalMode(false, "");
            }

            Process rawMode = new ProcessBuilder("sh", "-c", "stty -echo -icanon min 1 time 0 < /dev/tty")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (rawMode.waitFor() != 0) {
                return new TerminalMode(false, "");
            }

            return new TerminalMode(true, settings);
        } catch (Exception e) {
            return new TerminalMode(false, "");
        }
    }

    private static void restoreTerminal(TerminalMode mode) {
        if (mode == null || !mode.changed()) {
            return;
        }

        try {
            new ProcessBuilder("sh", "-c", "stty " + mode.settings() + " < /dev/tty")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor();
        } catch (Exception ignored) {
            // Best effort terminal restoration.
        }
    }

    private static ParsedLine parseLine(String input) throws ShellParseException {
        List<String> tokens = tokenize(input);
        boolean background = false;

        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equals("&")) {
            background = true;
            tokens.remove(tokens.size() - 1);
        }

        List<Command> commands = new ArrayList<>();
        Command current = new Command();

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals("|")) {
                addCommand(commands, current);
                current = new Command();
                continue;
            }

            if (isRedirectionOperator(token)) {
                if (i + 1 >= tokens.size()) {
                    throw new ShellParseException("syntax error: expected file after " + token);
                }
                current.redirections.add(toRedirection(token, tokens.get(++i)));
            } else {
                current.args.add(token);
            }
        }

        addCommand(commands, current);
        String original = input.trim();
        if (background && original.endsWith("&")) {
            original = original.substring(0, original.length() - 1).trim();
        }
        return new ParsedLine(commands, background, original);
    }

    private static void addCommand(List<Command> commands, Command command) {
        if (!command.args.isEmpty() || !command.redirections.isEmpty()) {
            commands.add(command);
        }
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (!inSingleQuotes && !inDoubleQuotes) {
                if (Character.isWhitespace(ch)) {
                    flushToken(tokens, current);
                    continue;
                }

                String operator = operatorAt(input, i);
                if (operator != null) {
                    flushToken(tokens, current);
                    tokens.add(operator);
                    i += operator.length() - 1;
                    continue;
                }

                if (ch == '\'') {
                    inSingleQuotes = true;
                    continue;
                }
                if (ch == '"') {
                    inDoubleQuotes = true;
                    continue;
                }
                if (ch == '\\') {
                    if (i + 1 < input.length()) {
                        current.append(input.charAt(++i));
                    } else {
                        current.append(ch);
                    }
                    continue;
                }
            } else if (inSingleQuotes) {
                if (ch == '\'') {
                    inSingleQuotes = false;
                } else {
                    current.append(ch);
                }
                continue;
            } else {
                if (ch == '"') {
                    inDoubleQuotes = false;
                    continue;
                }
                if (ch == '\\' && i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\' || next == '$' || next == '`' || next == '\n') {
                        current.append(next);
                        i++;
                    } else {
                        current.append(ch);
                    }
                    continue;
                }
            }

            current.append(ch);
        }

        flushToken(tokens, current);
        return tokens;
    }

    private static String operatorAt(String input, int index) {
        if (startsWith(input, index, "1>>") || startsWith(input, index, "2>>")) {
            return input.substring(index, index + 3);
        }
        if (startsWith(input, index, "1>") || startsWith(input, index, "2>") || startsWith(input, index, ">>")) {
            return input.substring(index, index + 2);
        }
        char ch = input.charAt(index);
        if (ch == '>' || ch == '|' || ch == '&') {
            return Character.toString(ch);
        }
        return null;
    }

    private static boolean startsWith(String input, int index, String value) {
        return index + value.length() <= input.length() && input.startsWith(value, index);
    }

    private static void flushToken(List<String> tokens, StringBuilder current) {
        if (current.length() > 0) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private static boolean isRedirectionOperator(String token) {
        return Set.of(">", "1>", "2>", ">>", "1>>", "2>>").contains(token);
    }

    private static Redirection toRedirection(String operator, String file) {
        int fd = operator.startsWith("2") ? 2 : 1;
        boolean append = operator.endsWith(">>");
        return new Redirection(fd, resolvePath(file), append);
    }

    private static void execute(ParsedLine parsed) throws Exception {
        List<Command> commands = parsed.commands();

        if (commands.size() == 1 && commands.get(0).args.isEmpty()) {
            return;
        }

        if (parsed.background()) {
            startBackgroundJob(parsed);
            return;
        }

        if (commands.size() == 1 && commands.get(0).isBuiltin()) {
            executeBuiltin(commands.get(0), true);
            return;
        }

        if (commands.size() == 1) {
            executeExternalForeground(commands.get(0));
        } else {
            executePipelineForeground(commands);
        }
    }

    private static void executeBuiltin(Command command, boolean allowDirectoryChange) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        runBuiltin(command, stdout, stderr, allowDirectoryChange);
        writeBuiltinStreams(command, stdout.toByteArray(), stderr.toByteArray());
    }

    private static void runBuiltin(
            Command command,
            OutputStream stdout,
            OutputStream stderr,
            boolean allowDirectoryChange
    ) throws IOException {
        PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8);
        String name = command.args.get(0);

        switch (name) {
            case "exit" -> System.exit(0);
            case "echo" -> out.println(String.join(" ", command.args.subList(1, command.args.size())));
            case "pwd" -> out.println(currentDirectory);
            case "type" -> runType(command, out);
            case "cd" -> runCd(command, err, allowDirectoryChange);
            case "jobs" -> runJobs(out);
            default -> err.println(name + ": command not found");
        }
    }

    private static void runType(Command command, PrintStream out) {
        if (command.args.size() < 2) {
            return;
        }

        String target = command.args.get(1);
        if (BUILTINS.contains(target)) {
            out.println(target + " is a shell builtin");
            return;
        }

        String executable = findExecutable(target);
        if (executable == null) {
            out.println(target + ": not found");
        } else {
            out.println(target + " is " + executable);
        }
    }

    private static void runCd(Command command, PrintStream err, boolean allowDirectoryChange) {
        if (!allowDirectoryChange || command.args.size() < 2) {
            return;
        }

        String target = command.args.get(1);
        Path targetPath = target.equals("~") ? homeDirectory() : resolvePath(target);

        if (Files.isDirectory(targetPath)) {
            currentDirectory = targetPath.toAbsolutePath().normalize();
        } else {
            err.println("cd: " + target + ": No such file or directory");
        }
    }

    private static void runJobs(PrintStream out) {
        reapCompletedJobs();
        List<Job> jobs = JOBS.values().stream()
                .sorted(Comparator.comparingInt(job -> job.number))
                .toList();

        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            String marker = i == jobs.size() - 1 ? "+" : i == jobs.size() - 2 ? "-" : " ";
            out.printf("[%d]%s  Running                 %s &%n", job.number, marker, job.commandLine);
        }
    }

    private static void writeBuiltinStreams(Command command, byte[] stdout, byte[] stderr) throws IOException {
        Redirection stdoutRedirect = lastRedirect(command, 1);
        Redirection stderrRedirect = lastRedirect(command, 2);

        if (stdoutRedirect == null) {
            System.out.write(stdout);
            System.out.flush();
        } else {
            writeFile(stdoutRedirect, stdout);
        }

        if (stderrRedirect == null) {
            System.err.write(stderr);
            System.err.flush();
        } else {
            writeFile(stderrRedirect, stderr);
        }
    }

    private static void executeExternalForeground(Command command) throws Exception {
        if (command.args.isEmpty()) {
            return;
        }

        if (findExecutable(command.args.get(0)) == null && !isExecutablePath(command.args.get(0))) {
            System.out.println(command.args.get(0) + ": command not found");
            return;
        }

        ProcessBuilder builder = processBuilder(command);
        applyProcessRedirections(builder, command);
        Process process = builder.start();
        process.waitFor();
    }

    private static void executePipelineForeground(List<Command> commands) throws Exception {
        byte[] input = new byte[0];

        for (int i = 0; i < commands.size(); i++) {
            Command command = commands.get(i);
            boolean last = i == commands.size() - 1;
            CapturedOutput output = runCommandForPipeline(command, input);

            Redirection stderrRedirect = lastRedirect(command, 2);
            if (stderrRedirect == null) {
                System.err.write(output.stderr());
                System.err.flush();
            } else {
                writeFile(stderrRedirect, output.stderr());
            }

            if (last) {
                Redirection stdoutRedirect = lastRedirect(command, 1);
                if (stdoutRedirect == null) {
                    System.out.write(output.stdout());
                    System.out.flush();
                } else {
                    writeFile(stdoutRedirect, output.stdout());
                }
            } else {
                input = output.stdout();
            }
        }
    }

    private record CapturedOutput(byte[] stdout, byte[] stderr) {
    }

    private static CapturedOutput runCommandForPipeline(Command command, byte[] input) throws Exception {
        if (command.args.isEmpty()) {
            return new CapturedOutput(input, new byte[0]);
        }

        if (command.isBuiltin()) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            runBuiltin(command, stdout, stderr, false);
            return new CapturedOutput(stdout.toByteArray(), stderr.toByteArray());
        }

        if (findExecutable(command.args.get(0)) == null && !isExecutablePath(command.args.get(0))) {
            return new CapturedOutput(new byte[0],
                    (command.args.get(0) + ": command not found\n").getBytes(StandardCharsets.UTF_8));
        }

        Process process = processBuilder(command).start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(input);
        }

        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        process.waitFor();
        return new CapturedOutput(stdout, stderr);
    }

    private static void startBackgroundJob(ParsedLine parsed) throws Exception {
        List<Command> commands = parsed.commands();

        if (commands.size() == 1 && commands.get(0).isBuiltin()) {
            executeBuiltin(commands.get(0), true);
            return;
        }

        List<Process> processes = startBackgroundProcesses(commands);
        if (processes.isEmpty()) {
            return;
        }

        int number = nextJobNumber();
        Job job = new Job(number, parsed.originalCommand(), processes);
        JOBS.put(number, job);

        System.out.println("[" + number + "] " + job.pid());

        Thread waiter = new Thread(() -> {
            for (Process process : processes) {
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            job.done.set(true);
        });
        waiter.setDaemon(true);
        waiter.start();
    }

    private static List<Process> startBackgroundProcesses(List<Command> commands) throws Exception {
        if (commands.size() == 1) {
            Command command = commands.get(0);
            if (findExecutable(command.args.get(0)) == null && !isExecutablePath(command.args.get(0))) {
                System.out.println(command.args.get(0) + ": command not found");
                return List.of();
            }

            ProcessBuilder builder = processBuilder(command);
            applyProcessRedirections(builder, command);
            Process process = builder.start();
            return List.of(process);
        }

        List<ProcessBuilder> builders = new ArrayList<>();
        for (Command command : commands) {
            if (command.isBuiltin()) {
                throw new ShellParseException("background pipelines with builtins are not supported");
            }
            if (findExecutable(command.args.get(0)) == null && !isExecutablePath(command.args.get(0))) {
                System.out.println(command.args.get(0) + ": command not found");
                return List.of();
            }
            builders.add(processBuilder(command));
        }

        ProcessBuilder first = builders.get(0);
        ProcessBuilder last = builders.get(builders.size() - 1);
        first.redirectInput(ProcessBuilder.Redirect.INHERIT);
        last.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        for (ProcessBuilder builder : builders) {
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        }
        applyProcessRedirections(last, commands.get(commands.size() - 1));
        return ProcessBuilder.startPipeline(builders);
    }

    private static ProcessBuilder processBuilder(Command command) {
        return new ProcessBuilder(command.args).directory(currentDirectory.toFile());
    }

    private static void applyProcessRedirections(ProcessBuilder builder, Command command) {
        Redirection stdoutRedirect = lastRedirect(command, 1);
        Redirection stderrRedirect = lastRedirect(command, 2);

        builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        builder.redirectOutput(stdoutRedirect == null
                ? ProcessBuilder.Redirect.INHERIT
                : redirectFor(stdoutRedirect));
        builder.redirectError(stderrRedirect == null
                ? ProcessBuilder.Redirect.INHERIT
                : redirectFor(stderrRedirect));
    }

    private static ProcessBuilder.Redirect redirectFor(Redirection redirection) {
        File file = redirection.target().toFile();
        return redirection.append()
                ? ProcessBuilder.Redirect.appendTo(file)
                : ProcessBuilder.Redirect.to(file);
    }

    private static Redirection lastRedirect(Command command, int fd) {
        Redirection result = null;
        for (Redirection redirection : command.redirections) {
            if (redirection.fd() == fd) {
                result = redirection;
            }
        }
        return result;
    }

    private static void writeFile(Redirection redirection, byte[] data) throws IOException {
        Files.createDirectories(Optional.ofNullable(redirection.target().getParent()).orElse(currentDirectory));
        if (redirection.append()) {
            Files.write(redirection.target(), data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.write(redirection.target(), data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private static void reapCompletedJobs() {
        Set<Integer> completed = new HashSet<>();
        for (Map.Entry<Integer, Job> entry : JOBS.entrySet()) {
            if (entry.getValue().done.get() || entry.getValue().processes.stream().allMatch(process -> !process.isAlive())) {
                completed.add(entry.getKey());
            }
        }
        completed.forEach(JOBS::remove);
    }

    private static int nextJobNumber() {
        OptionalInt firstFree = java.util.stream.IntStream.iterate(1, n -> n + 1)
                .filter(number -> !JOBS.containsKey(number))
                .findFirst();
        return firstFree.orElse(JOBS.size() + 1);
    }

    private static Path resolvePath(String value) {
        Path path = Paths.get(value);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return currentDirectory.resolve(path).normalize();
    }

    private static Path homeDirectory() {
        String home = System.getenv("HOME");
        if (home == null || home.isEmpty()) {
            home = System.getProperty("user.home");
        }
        return Paths.get(home).toAbsolutePath().normalize();
    }

    private static String findExecutable(String command) {
        if (isExecutablePath(command)) {
            return resolvePath(command).toString();
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }

        for (String dir : pathEnv.split(File.pathSeparator)) {
            File file = new File(dir, command);
            if (file.exists() && file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    private static boolean isExecutablePath(String command) {
        if (!command.contains("/") && !command.contains("\\")) {
            return false;
        }
        Path path = resolvePath(command);
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    private static class ShellParseException extends Exception {
        private ShellParseException(String message) {
            super(message);
        }
    }
}
