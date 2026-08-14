package team.xenobyte.modern.bootstrap;

public final class BootstrapSmokeMain {
    private BootstrapSmokeMain() {
    }

    public static void main(String[] args) {
        String jarPath = args.length > 0 ? args[0] : "";
        try {
            NativeBootstrap.initFromNative("smoke-test", jarPath);
            System.err.println("Unexpected success: NativeBootstrap found a Minecraft/Forge classloader outside Minecraft");
            System.exit(1);
        } catch (Throwable throwable) {
            String message = String.valueOf(throwable.getMessage());
            if (throwable instanceof IllegalStateException && message.contains("classloader")) {
                System.out.println("Expected smoke-test failure: " + message);
                System.exit(0);
            }

            System.err.println("Unexpected smoke-test failure:");
            throwable.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
