package team.xenobyte.modern.gui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class ModuleMessageLog {
    private static final int LIMIT = 3;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();

    private ModuleMessageLog() {
    }

    public static synchronized void push(String module, String message) {
        String cleanModule = module == null || module.isBlank() ? "Module" : module;
        String cleanMessage = message == null ? "" : message;
        ENTRIES.addFirst(new Entry(LocalTime.now().format(TIME), cleanModule, cleanMessage));
        while (ENTRIES.size() > LIMIT) {
            ENTRIES.removeLast();
        }
    }

    public static synchronized List<Entry> snapshot() {
        return new ArrayList<>(ENTRIES);
    }

    public record Entry(String time, String module, String message) {
    }
}
