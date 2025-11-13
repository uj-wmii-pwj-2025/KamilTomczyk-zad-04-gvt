package uj.wmii.pwj.gvt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

public class Gvt {

    private final ExitHandler terminator;
    private final Path workingDirectory;
    private final Path storageDirectory;

    private static final String GVT_DIR_NAME = ".gvt";
    private static final String MESSAGE_FILE_NAME = ".gvt_message";
    private static final String LATEST_VERSION_FILE = ".gvt_latest";
    private static final String ACTIVE_VERSION_FILE = ".gvt_active";


    public Gvt(ExitHandler exitHandler) {
        this.terminator = exitHandler;
        this.workingDirectory = Paths.get("").toAbsolutePath();
        this.storageDirectory = this.workingDirectory.resolve(GVT_DIR_NAME);
    }

    public static void main(String... args) {
        new Gvt(new ExitHandler()).mainInternal(args);
    }

    void mainInternal(String... args) {
        if (args.length == 0) {
            terminator.exit(1, "Please specify command.");
            return;
        }

        String command = args[0].toLowerCase();
        String[] params = Arrays.copyOfRange(args, 1, args.length);

        if (!command.equals("init") && !isInitialized()) {
            terminator.exit(-2, "Current directory is not initialized. Please use init command to initialize.");
            return;
        }

        try {
            switch (command) {
                case "init":
                    handleInit();
                    break;
                case "add":
                    handleAdd(params);
                    break;
                case "detach":
                    handleDetach(params);
                    break;
                case "commit":
                    handleCommit(params);
                    break;
                case "checkout":
                    handleCheckout(params);
                    break;
                case "history":
                    handleHistory(params);
                    break;
                case "version":
                    handleVersion(params);
                    break;
                default:
                    terminator.exit(1, "Unknown command " + args[0] + ".");
            }
        } catch (IOException e) {
            e.printStackTrace(System.err);
            terminator.exit(-3, "Underlying system problem. See ERR for details.");
        }
    }

    private boolean isInitialized() {
        return Files.isDirectory(storageDirectory);
    }

    private void handleInit() throws IOException {
        if (isInitialized()) {
            terminator.exit(10, "Current directory is already initialized.");
            return;
        }
        Files.createDirectory(storageDirectory);
        Files.writeString(storageDirectory.resolve(LATEST_VERSION_FILE), "0");
        Files.writeString(storageDirectory.resolve(ACTIVE_VERSION_FILE), "0");
        Path v0 = storageDirectory.resolve("0");
        Files.createDirectory(v0);
        Files.writeString(v0.resolve(MESSAGE_FILE_NAME), "GVT initialized.");
        terminator.exit(0, "Current directory initialized successfully.");
    }

    private void handleAdd(String... params) {
        if (params.length == 0 || (params.length > 0 && params[0].equals("-m"))) {
            terminator.exit(20, "Please specify file to add.");
            return;
        }
        String fileName = params[0];
        Path file = workingDirectory.resolve(fileName);

        if (!Files.exists(file)) {
            terminator.exit(21, "File not found. File: " + fileName);
            return;
        }

        try {
            int latestVersion = getLatestVersion();
            Path latestVersionDir = storageDirectory.resolve(String.valueOf(latestVersion));

            if (Files.exists(latestVersionDir.resolve(fileName))) {
                terminator.exit(0, "File already added. File: " + fileName);
                return;
            }

            int newVersion = latestVersion + 1;
            Path newVersionDir = createVersionDirectory(newVersion, latestVersionDir);
            Files.copy(file, newVersionDir.resolve(fileName));

            String defaultMessage = "File added successfully. File: " + fileName;
            String commitMessage = buildCommitMessage(defaultMessage, params);

            finalizeNewVersion(newVersion, commitMessage);
            terminator.exit(0, "File added successfully. File: " + fileName);
        } catch (IOException e) {
            e.printStackTrace(System.err);
            terminator.exit(22, "File cannot be added. See ERR for details. File: " + fileName);
        }
    }

    private void handleDetach(String... params) {
        if (params.length == 0 || (params.length > 0 && params[0].equals("-m"))) {
            terminator.exit(30, "Please specify file to detach.");
            return;
        }
        String fileName = params[0];

        try {
            int latestVersion = getLatestVersion();
            Path latestVersionDir = storageDirectory.resolve(String.valueOf(latestVersion));

            if (!Files.exists(latestVersionDir.resolve(fileName))) {
                terminator.exit(0, "File is not added to gvt. File: " + fileName);
                return;
            }

            int newVersion = latestVersion + 1;
            Path newVersionDir = createVersionDirectory(newVersion, latestVersionDir);
            Files.delete(newVersionDir.resolve(fileName));

            String defaultMessage = "File detached successfully. File: " + fileName;
            String commitMessage = buildCommitMessage(defaultMessage, params);

            finalizeNewVersion(newVersion, commitMessage);
            terminator.exit(0, "File detached successfully. File: " + fileName);
        } catch (IOException e) {
            e.printStackTrace(System.err);
            terminator.exit(31, "File cannot be detached, see ERR for details. File: " + fileName);
        }
    }

    private void handleCommit(String... params) {
        if (params.length == 0 || (params.length > 0 && params[0].equals("-m"))) {
            terminator.exit(50, "Please specify file to commit.");
            return;
        }
        String fileName = params[0];
        Path file = workingDirectory.resolve(fileName);

        if (!Files.exists(file)) {
            terminator.exit(51, "File not found. File: " + fileName);
            return;
        }

        try {
            int latestVersion = getLatestVersion();
            Path latestVersionDir = storageDirectory.resolve(String.valueOf(latestVersion));

            if (!Files.exists(latestVersionDir.resolve(fileName))) {
                terminator.exit(0, "File is not added to gvt. File: " + fileName);
                return;
            }

            int newVersion = latestVersion + 1;
            Path newVersionDir = createVersionDirectory(newVersion, latestVersionDir);
            Files.copy(file, newVersionDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);

            String defaultMessage = "File committed successfully. File: " + fileName;
            String commitMessage = buildCommitMessage(defaultMessage, params);

            finalizeNewVersion(newVersion, commitMessage);
            terminator.exit(0, "File committed successfully. File: " + fileName);
        } catch (IOException e) {
            e.printStackTrace(System.err);
            terminator.exit(52, "File cannot be committed, see ERR for details. File: " + fileName);
        }
    }

    private void handleCheckout(String... params) throws IOException {
        if (params.length != 1) {
            terminator.exit(60, "Invalid version number: " + (params.length > 0 ? params[0] : ""));
            return;
        }
        int version;
        try {
            version = Integer.parseInt(params[0]);
        } catch (NumberFormatException e) {
            terminator.exit(60, "Invalid version number: " + params[0]);
            return;
        }

        if (version < 0 || version > getLatestVersion()) {
            terminator.exit(60, "Invalid version number: " + version);
            return;
        }

        Path targetVersionDir = storageDirectory.resolve(String.valueOf(version));
        Path activeVersionDir = storageDirectory.resolve(String.valueOf(getActiveVersion()));

        try (Stream<Path> stream = Files.list(activeVersionDir)) {
            stream.filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(workingDirectory.resolve(p.getFileName()));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch(UncheckedIOException e) {
            throw e.getCause();
        }

        copyDirectoryContents(targetVersionDir, workingDirectory);
        setActiveVersion(version);
        terminator.exit(0, "Checkout successful for version: " + version);
    }

    private void handleHistory(String... params) throws IOException {
        int latestVersion = getLatestVersion();
        int limit = latestVersion + 1;
        if (params.length >= 2 && "-last".equals(params[0])) {
            try {
                limit = Integer.parseInt(params[1]);
            } catch (NumberFormatException e) {

            }
        }

        StringBuilder history = new StringBuilder();
        for (int i = latestVersion; i >= 0 && (latestVersion - i) < limit; i--) {
            Path versionDir = storageDirectory.resolve(String.valueOf(i));
            String message = Files.readAllLines(versionDir.resolve(MESSAGE_FILE_NAME)).get(0);
            history.append(i).append(": ").append(message).append("\n");
        }
        terminator.exit(0, history.toString());
    }

    private void handleVersion(String... params) throws IOException {
        int version;
        try {
            version = params.length > 0 ? Integer.parseInt(params[0]) : getActiveVersion();
        } catch (NumberFormatException e) {
            terminator.exit(60, "Invalid version number: " + (params.length > 0 ? params[0] : ""));
            return;
        }

        if (version < 0 || version > getLatestVersion()) {
            terminator.exit(60, "Invalid version number: " + version);
            return;
        }

        Path versionDir = storageDirectory.resolve(String.valueOf(version));
        String message = Files.readString(versionDir.resolve(MESSAGE_FILE_NAME));
        terminator.exit(0, "Version: " + version + "\n" + message);
    }

    private int getLatestVersion() throws IOException {
        return Integer.parseInt(Files.readString(storageDirectory.resolve(LATEST_VERSION_FILE)));
    }

    private int getActiveVersion() throws IOException {
        return Integer.parseInt(Files.readString(storageDirectory.resolve(ACTIVE_VERSION_FILE)));
    }

    private void setActiveVersion(int version) throws IOException {
        Files.writeString(storageDirectory.resolve(ACTIVE_VERSION_FILE), String.valueOf(version));
    }

    private Path createVersionDirectory(int newVersion, Path sourceDir) throws IOException {
        Path newDir = storageDirectory.resolve(String.valueOf(newVersion));
        Files.createDirectory(newDir);
        copyDirectoryContents(sourceDir, newDir);
        return newDir;
    }

    private void finalizeNewVersion(int version, String message) throws IOException {
        Path versionDir = storageDirectory.resolve(String.valueOf(version));
        Files.writeString(versionDir.resolve(MESSAGE_FILE_NAME), message);
        Files.writeString(storageDirectory.resolve(LATEST_VERSION_FILE), String.valueOf(version));
        setActiveVersion(version);
    }

    private String buildCommitMessage(String defaultMessage, String[] params) {
        return parseUserMessage(params).orElse(defaultMessage);
    }

    private Optional<String> parseUserMessage(String[] params) {
        for (int i = 0; i < params.length - 1; i++) {
            if ("-m".equals(params[i])) {
                return Optional.of(params[i+1]);
            }
        }
        return Optional.empty();
    }

    private void copyDirectoryContents(Path source, Path destination) throws IOException {
        try (Stream<Path> stream = Files.list(source)) {
            stream.filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(p -> {
                        try {
                            Files.copy(p, destination.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch(UncheckedIOException e) {
            throw e.getCause();
        }
    }
}