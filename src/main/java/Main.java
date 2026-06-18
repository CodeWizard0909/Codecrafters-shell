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

            String[] parts = command.split("\\s+");
            String cmd = parts[0];

            if (cmd.equals("exit")) {
                System.exit(parts.length > 1 ? Integer.parseInt(parts[1]) : 0);

            } else if (cmd.equals("echo")) {
                String[] echoArgs = Arrays.copyOfRange(parts, 1, parts.length);
                System.out.println(String.join(" ", echoArgs));
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

    private static void handleCd(String path) {
        File dir;

        if (path.equals("~")) {
            String home = System.getenv("HOME");
            if (home == null) home = System.getProperty("user.home");
            dir = new File(home);
        } else if (path.startsWith("/")) {
            // Absolute path
            dir = new File(path);
        } else {
            // Relative path (handles ./, ../, subdir names, etc.)
            dir = new File(currentDirectory, path);
        }

        // Normalize to resolve ./ and ../
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
                // Use command name as-is (not absolute path) as argv[0]
                List<String> cmd = new ArrayList<>();
                cmd.add(parts[0]);
                for (int i = 1; i < parts.length; i++) cmd.add(parts[i]);
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
