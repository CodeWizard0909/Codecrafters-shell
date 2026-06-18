import java.io.*;
import java.util.*;

public class Main {
    private static String currentDirectory = System.getProperty("user.dir");
    private static final List<BackgroundJob> backgroundJobs = new ArrayList<>();

    private static class BackgroundJob {
        int jobNumber;
        long pid;
        String command;
        Process process;

        BackgroundJob(int jobNumber, long pid, String command, Process process) {
            this.jobNumber = jobNumber;
            this.pid = pid;
            this.command = command;
            this.process = process;
        }
    }

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            // Reap completed background jobs before showing prompt
            reapDoneJobs();
            System.out.print("$ ");
            System.out.flush();

            String command = reader.readLine();
            if (command == null) break;

            command = command.trim();
            if (command.isEmpty()) continue;

            // Quote-aware tokenizer
            List<String> tokens = parseArgs(command);
            if (tokens.isEmpty()) continue;

            // Extract stdout redirect (>/1>/>>/ 1>>) and stderr redirect (2>/2>>) if present
            String stdoutFile = null;
            boolean stdoutAppend = false;
            String stderrFile = null;
            boolean stderrAppend = false;
            boolean runInBackground = false;

            // Check if last token is &
            if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equals("&")) {
                runInBackground = true;
                tokens.remove(tokens.size() - 1);
            }

            // Check for pipeline: split tokens by |
            List<List<String>> pipelineSegments = new ArrayList<>();
            List<String> currentSegment = new ArrayList<>();
            for (String t : tokens) {
                if (t.equals("|")) {
                    pipelineSegments.add(currentSegment);
                    currentSegment = new ArrayList<>();
                } else {
                    currentSegment.add(t);
                }
            }
            pipelineSegments.add(currentSegment);

            if (pipelineSegments.size() > 1) {
                // Handle pipeline
                handlePipeline(pipelineSegments, runInBackground);
                continue;
            }

            // Single command (no pipeline) - extract redirects from tokens
            List<String> cmdTokens = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                String t = tokens.get(i);
                if ((t.equals(">>") || t.equals("1>>")) && i + 1 < tokens.size()) {
                    stdoutFile = tokens.get(++i);
                    stdoutAppend = true;
                } else if ((t.equals(">") || t.equals("1>")) && i + 1 < tokens.size()) {
                    stdoutFile = tokens.get(++i);
                    stdoutAppend = false;
                } else if (t.equals("2>>") && i + 1 < tokens.size()) {
                    stderrFile = tokens.get(++i);
                    stderrAppend = true;
                } else if (t.equals("2>") && i + 1 < tokens.size()) {
                    stderrFile = tokens.get(++i);
                    stderrAppend = false;
                } else {
                    cmdTokens.add(t);
                }
            }
            if (cmdTokens.isEmpty()) continue;

            String cmd = cmdTokens.get(0);
            String[] parts = cmdTokens.toArray(new String[0]);

            // Set up stdout/stderr redirect for builtins
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            if (stdoutFile != null) {
                PrintStream ps = new PrintStream(new FileOutputStream(stdoutFile, stdoutAppend));
                System.setOut(ps);
            }
            if (stderrFile != null) {
                PrintStream ps = new PrintStream(new FileOutputStream(stderrFile, stderrAppend));
                System.setErr(ps);
            }

            try {
                if (cmd.equals("exit")) {
                    System.exit(parts.length > 1 ? Integer.parseInt(parts[1]) : 0);

                } else if (cmd.equals("echo")) {
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

                } else if (cmd.equals("jobs")) {
                    // List all jobs in order, showing Running or Done
                    int currentJobNum = backgroundJobs.isEmpty() ? -1 : backgroundJobs.get(backgroundJobs.size() - 1).jobNumber;
                    int previousJobNum = backgroundJobs.size() < 2 ? -1 : backgroundJobs.get(backgroundJobs.size() - 2).jobNumber;
                    List<BackgroundJob> doneJobs = new ArrayList<>();
                    for (BackgroundJob job : backgroundJobs) {
                        String marker;
                        if (job.jobNumber == currentJobNum) {
                            marker = "+";
                        } else if (job.jobNumber == previousJobNum) {
                            marker = "-";
                        } else {
                            marker = " ";
                        }
                        if (job.process.isAlive()) {
                            String status = String.format("%-24s", "Running");
                            System.out.println("[" + job.jobNumber + "]" + marker + "  " + status + job.command + " &");
                        } else {
                            String status = String.format("%-24s", "Done");
                            System.out.println("[" + job.jobNumber + "]" + marker + "  " + status + job.command);
                            doneJobs.add(job);
                        }
                    }
                    backgroundJobs.removeAll(doneJobs);
                    System.out.flush();

                } else {
                    handleExternal(parts, command, stdoutFile, stdoutAppend, stderrFile, stderrAppend, runInBackground);
                    System.out.flush();
                }
            } finally {
                if (stdoutFile != null) {
                    System.out.flush();
                    System.setOut(originalOut);
                }
                if (stderrFile != null) {
                    System.err.flush();
                    System.setErr(originalErr);
                }
            }
        }
    }

    /**
     * Tokenizes a shell command line respecting single-quote and double-quote rules:
     *  - Inside single quotes: every character is literal (no special meaning at all).
     *  - Inside double quotes: most characters are literal; spaces preserved;
     *    single quotes inside are literal. Only \" and \\ are escape sequences.
     *  - Outside quotes: \ escapes the next character (removes backslash, next char literal).
     *  - Adjacent quoted/unquoted segments are concatenated into one token.
     *  - Outside quotes, whitespace separates tokens.
     */
    private static List<String> parseArgs(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean hasToken = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inSingleQuote) {
                if (c == '\'') {
                    inSingleQuote = false;
                } else {
                    current.append(c);
                    hasToken = true;
                }
            } else if (inDoubleQuote) {
                if (c == '"') {
                    inDoubleQuote = false;
                } else if (c == '\\' && i + 1 < line.length()) {
                    char next = line.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        i++;
                        current.append(next);
                    } else {
                        current.append(c);
                    }
                    hasToken = true;
                } else {
                    current.append(c);
                    hasToken = true;
                }
            } else {
                if (c == '\\') {
                    if (i + 1 < line.length()) {
                        i++;
                        current.append(line.charAt(i));
                        hasToken = true;
                    }
                } else if (c == '\'') {
                    inSingleQuote = true;
                    hasToken = true;
                } else if (c == '"') {
                    inDoubleQuote = true;
                    hasToken = true;
                } else if (c == ' ' || c == '\t') {
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
        Set<String> builtins = new HashSet<>(Arrays.asList("echo", "exit", "type", "pwd", "cd", "jobs"));
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

    private static void handleExternal(String[] parts, String originalCommand, String stdoutFile, boolean stdoutAppend, String stderrFile, boolean stderrAppend, boolean runInBackground) throws Exception {
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
                if (stdoutFile != null) {
                    pb.redirectOutput(stdoutAppend
                        ? ProcessBuilder.Redirect.appendTo(new File(stdoutFile))
                        : ProcessBuilder.Redirect.to(new File(stdoutFile)));
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }
                if (stderrFile != null) {
                    pb.redirectError(stderrAppend
                        ? ProcessBuilder.Redirect.appendTo(new File(stderrFile))
                        : ProcessBuilder.Redirect.to(new File(stderrFile)));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }
                Process process = pb.start();
                if (runInBackground) {
                    // Find the smallest available job number
                    Set<Integer> usedNumbers = new HashSet<>();
                    for (BackgroundJob bj : backgroundJobs) {
                        usedNumbers.add(bj.jobNumber);
                    }
                    int nextJobNum = 1;
                    while (usedNumbers.contains(nextJobNum)) {
                        nextJobNum++;
                    }
                    // Build the command string without the trailing " &"
                    String cmdStr = originalCommand.trim();
                    if (cmdStr.endsWith("&")) {
                        cmdStr = cmdStr.substring(0, cmdStr.length() - 1).trim();
                    }
                    BackgroundJob newJob = new BackgroundJob(nextJobNum, process.pid(), cmdStr, process);
                    // Insert in sorted position by job number
                    int insertIdx = 0;
                    for (int idx = 0; idx < backgroundJobs.size(); idx++) {
                        if (backgroundJobs.get(idx).jobNumber > nextJobNum) {
                            break;
                        }
                        insertIdx = idx + 1;
                    }
                    backgroundJobs.add(insertIdx, newJob);
                    System.out.println("[" + nextJobNum + "] " + process.pid());
                    System.out.flush();
                } else {
                    process.waitFor();
                }
                return;
            }
        }
        System.out.println(parts[0] + ": command not found");
    }

    /**
     * Reap completed background jobs: print Done status and remove from the list.
     */
    private static void reapDoneJobs() {
        List<BackgroundJob> doneJobs = new ArrayList<>();
        for (BackgroundJob job : backgroundJobs) {
            if (!job.process.isAlive()) {
                doneJobs.add(job);
            }
        }
        if (!doneJobs.isEmpty()) {
            // Compute markers based on current job list (before removal)
            int currentJobNum = backgroundJobs.get(backgroundJobs.size() - 1).jobNumber;
            int previousJobNum = backgroundJobs.size() < 2 ? -1 : backgroundJobs.get(backgroundJobs.size() - 2).jobNumber;
            for (BackgroundJob job : doneJobs) {
                String marker;
                if (job.jobNumber == currentJobNum) {
                    marker = "+";
                } else if (job.jobNumber == previousJobNum) {
                    marker = "-";
                } else {
                    marker = " ";
                }
                String status = String.format("%-24s", "Done");
                System.out.println("[" + job.jobNumber + "]" + marker + "  " + status + job.command);
            }
            backgroundJobs.removeAll(doneJobs);
        }
    }
    private static void handlePipeline(List<List<String>> segments, boolean runInBackground) throws Exception {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            System.out.println("PATH environment variable not set");
            return;
        }

        // We will build a list of process builders
        List<ProcessBuilder> builders = new ArrayList<>();

        for (List<String> segment : segments) {
            if (segment.isEmpty()) continue;
            String cmdName = segment.get(0);
            File execFile = null;
            for (String dir : pathEnv.split(File.pathSeparator)) {
                File file = new File(dir, cmdName);
                if (file.isFile() && file.canExecute()) {
                    execFile = file;
                    break;
                }
            }
            if (execFile == null) {
                System.out.println(cmdName + ": command not found");
                return;
            }
            ProcessBuilder pb = new ProcessBuilder(segment);
            pb.directory(new File(currentDirectory));
            pb.environment().put("PATH", pathEnv);
            builders.add(pb);
        }

        if (builders.isEmpty()) return;

        // In Java 9+, ProcessBuilder.startPipeline(List<ProcessBuilder>) executes a pipeline!
        // Let's use startPipeline to handle the piping automatically.
        List<Process> processes = ProcessBuilder.startPipeline(builders);
        
        if (runInBackground) {
            // For background pipeline, we'll keep track of the last process or all of them.
            // Let's assume the last process represents the pipeline job status.
            Process lastProcess = processes.get(processes.size() - 1);
            Set<Integer> usedNumbers = new HashSet<>();
            for (BackgroundJob bj : backgroundJobs) {
                usedNumbers.add(bj.jobNumber);
            }
            int nextJobNum = 1;
            while (usedNumbers.contains(nextJobNum)) {
                nextJobNum++;
            }

            // Build original pipeline command string
            StringBuilder cmdStrBuilder = new StringBuilder();
            for (int i = 0; i < segments.size(); i++) {
                if (i > 0) cmdStrBuilder.append(" | ");
                cmdStrBuilder.append(String.join(" ", segments.get(i)));
            }
            String cmdStr = cmdStrBuilder.toString();

            BackgroundJob newJob = new BackgroundJob(nextJobNum, lastProcess.pid(), cmdStr, lastProcess);
            int insertIdx = 0;
            for (int idx = 0; idx < backgroundJobs.size(); idx++) {
                if (backgroundJobs.get(idx).jobNumber > nextJobNum) {
                    break;
                }
                insertIdx = idx + 1;
            }
            backgroundJobs.add(insertIdx, newJob);
            System.out.println("[" + nextJobNum + "] " + lastProcess.pid());
            System.out.flush();
        } else {
            for (Process p : processes) {
                p.waitFor();
            }
        }
    }
}
