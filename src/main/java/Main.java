import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("$ ");
            System.out.flush();
            String input = scanner.nextLine().trim();
            if (input.equals("exit") || input.startsWith("exit ")) {
                break;
            }
            System.out.println(input + ": command not found");
        }
    }
}
