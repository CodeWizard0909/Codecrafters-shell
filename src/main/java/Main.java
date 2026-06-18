import java.io.*;
import java.util.*;

public class Main {
    private static String currentDirectory = System.getProperty("user.dir");

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            String command = reader.readLine();
            if (command == null) break;

            command = command.trim();
            if (command.isEmpty()) continue;

            // Use quote-aware tokenizer instead of simple split
            List<String> tokens = parseArgs(command);
            if (tokens.isEmpty()) continue;

            String cmd = tokens.get(0);
            String[] parts = tokens.toArray(new String[0]);

            if (cmd.equals("exit")) {
                System.exit(parts.length > 1 ? Integer.parseInt(parts[1]) : 0);

            } else if (cmd.equals("echo")) {
                // Join all arguments after "echo" with a single space
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < parts.length; i++) {
                    if (i > 1) sb.append(' ');
                    sb.append(parts[i]);
                }
                System.out.println(sb.toString());
                System.out.flush();

            } else if (cmd.equals("pwd")) {
                System.out.println(currentDirectory);
                System.out.flush();

            } else if (cmd.equals("type")) {
                if (parts.length >= 2) {
                    handleType(parts[1]);
                }
                System.out.flush();

            } else if (cmd.equals("cd")) {
                if (parts.length >= 2) {
                    handleCd(parts[1]);
                }
                System.out.flush();

            } else {
                handleExternal(parts);
                System.out.flush();
            }
        }
    }

    /**
     * Tokenizes a shell command line respecting single-quote rules:
     *  - Characters inside single quotes are treated literally (no splitting on spaces).
     *  - Adjacent quoted/unquoted segments are concatenated into one token.
     *  - Outside quotes, whitespace separates tokens.
     */
    private static List<String> parseArgs(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean hasToken = false; // tracks whether we started building a token

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inSingleQuote) {
                if (c == '\'') {
                    // End of single-quoted section
                    inSingleQuote = false;
                } else {
                    current.append(c);
                    hasToken = true;
                }
            } else {
                if (c == '\'') {
                    // Start of single-quoted section
                    inSingleQuote = true;
                    hasToken = true; // even empty '' counts as starting a token
                } else if (c == ' ' || c == '\t') {
                    // Whitespace outside quotes → token boundary
                    if (hasToken) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        hasToken = false;
                    }
                } else {
                    current.append(c);
                    hasToken = true;
                }
            }
        }

        // Add last token if any
        if (hasToken) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private static void handleCd(String path) {
        File dir;

        if (path.equals("~")) {
            String home = System.getenv("HOME");
            if (home == null) home = System.getProperty("user.home");
            dir = new File(home);
        } else if (path.startsWith("/")) {
            dir = new File(path);
        } else {
            dir = new File(currentDirectory, path);
        }

        try {
            dir = dir.getCanonicalFile();
        } catch (IOException e) {
            System.out.println("cd: " + path + ": No such file or directory");
            return;
        }

        if (dir.exists() && dir.isDirectory()) {
            currentDirectory = dir.getAbsolutePath();
        } else {
            System.out.println("cd: " + path + ": No such file or directory");
        }
    }

    private static void handleType(String arg) {
        Set<String> builtins = new HashSet<>(Arrays.asList("echo", "exit", "type", "pwd", "cd"));
        if (builtins.contains(arg)) {
            System.out.println(arg + " is a shell builtin");
            return;
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                File file = new File(dir, arg);
                if (file.isFile() && file.canExecute()) {
                    System.out.println(arg + " is " + file.getAbsolutePath());
                    return;
                }
            }
        }
        System.out.println(arg + ": not found");
    }

    private static void handleExternal(String[] parts) throws Exception {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            System.out.println(parts[0] + ": command not found");
            return;
        }

        for (String dir : pathEnv.split(File.pathSeparator)) {
            File file = new File(dir, parts[0]);
            if (file.isFile() && file.canExecute()) {
                List<String> cmd = new ArrayList<>(Arrays.asList(parts));
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(new File(currentDirectory));
                pb.environment().put("PATH", pathEnv);
                pb.inheritIO();
                pb.start().waitFor();
                return;
            }
        }
        System.out.println(parts[0] + ": command not found");
    }
}
