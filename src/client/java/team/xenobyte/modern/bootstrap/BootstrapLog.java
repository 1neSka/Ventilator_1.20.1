package team.xenobyte.modern.bootstrap;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

public final class BootstrapLog {
    private static final Path LOG_PATH = Path.of(
        System.getProperty("java.io.tmpdir"),
        "xenobyte-modern-java.log"
    );

    private BootstrapLog() {
    }

    public static void info(String message) {
        write("INFO", message);
    }

    public static void error(String message, Throwable throwable) {
        StringWriter stack = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stack));
        write("ERROR", message + System.lineSeparator() + stack);
    }

    private static synchronized void write(String level, String message) {
        String line = "[" + LocalDateTime.now() + "] [" + level + "] " + message + System.lineSeparator();
        System.out.print("[xenobyte-modern] " + line);
        try {
            Files.writeString(
                LOG_PATH,
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (Exception ignored) {
            // Avoid failing bootstrap because logging failed.
        }
    }
}
