package team.xenobyte.modern.bootstrap;

import net.minecraft.client.Minecraft;
import team.xenobyte.modern.XenobyteModernClient;

public final class GameBootstrap {
    private GameBootstrap() {
    }

    public static void initOnGameThread(String source) {
        BootstrapLog.info("GameBootstrap requested, source=" + source + ", thread=" + Thread.currentThread().getName());
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            BootstrapLog.info("Minecraft instance is null; running bootstrap on current thread");
            runBootstrap(source);
            return;
        }

        BootstrapLog.info("Scheduling bootstrap on Minecraft client thread");
        client.execute(() -> runBootstrap(source));
    }

    private static void runBootstrap(String source) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        ClassLoader bootstrapLoader = GameBootstrap.class.getClassLoader();

        try {
            thread.setContextClassLoader(bootstrapLoader);
            XenobyteModernClient.bootstrap(source);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
