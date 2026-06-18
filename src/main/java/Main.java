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
                if (cmd.equals("exit") || cmd.equals("echo") || cmd.equals("type")) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    String path = null;
                    for (String dir : System.getenv("PATH").split(File.pathSeparator)) {
                        File f = new File(dir, cmd);
                        if (f.isFile() && f.canExecute()) {
                            path = f.getPath();
                            break;
                        }
                    }
                    System.out.println(path != null ? cmd + " is " + path : cmd + ": not found");
                }
            } else {
                System.out.println(input + ": command not found");
            }
        }
    }
}
