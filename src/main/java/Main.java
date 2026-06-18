import java.io.File;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("$ ");
            System.out.flush();
            String input = sc.nextLine().trim();
            if (input.equals("exit") || input.startsWith("exit ")) break;
            
            if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
            } else if (input.startsWith("type ")) {
                String cmd = input.substring(5).trim();
                if (cmd.equals("exit") || cmd.equals("echo") || cmd.equals("type") || cmd.equals("pwd")) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    String path = getPath(cmd);
                    System.out.println(path != null ? cmd + " is " + path : cmd + ": not found");
                }
            } else if (input.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
            } else {
                String[] parts = input.split("\\s+");
                String path = getPath(parts[0]);
                if (path != null) {
                    ProcessBuilder pb = new ProcessBuilder(parts);
                    pb.inheritIO();
                    pb.start().waitFor();
                } else {
                    System.out.println(parts[0] + ": command not found");
                }
            }
        }
    }

    private static String getPath(String cmd) {
        if (cmd.startsWith("/") || cmd.startsWith("./") || cmd.startsWith("../")) {
            File f = new File(cmd);
            if (f.isFile() && f.canExecute()) return f.getPath();
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                File f = new File(dir, cmd);
                if (f.isFile() && f.canExecute()) return f.getPath();
            }
        }
        return null;
    }
}
