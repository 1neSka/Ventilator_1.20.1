package team.xenobyte.modern.bootstrap;

import java.util.HashSet;
import java.util.Set;

public final class RenderDiagnostics {
    private static final Set<String> LOGGED = new HashSet<>();

    private RenderDiagnostics() {
    }

    public static synchronized void once(String key, String message) {
        if (LOGGED.add(key)) {
            BootstrapLog.info(message);
        }
    }
}
