package team.xenobyte.modern.module.impl;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import team.xenobyte.modern.XenobyteModernClient;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class BlackoutModule extends XenoModule {
    private static final long MAINTENANCE_INTERVAL_NANOS = 1_000_000_000L;
    private static final int EXIT_KEY = GLFW.GLFW_KEY_F12;

    private final ModuleSetting statusWindow;
    private boolean previousNoRender;
    private boolean previousPauseOnLostFocus;
    private boolean stateCaptured;
    private boolean rendererSuppressed;
    private volatile boolean blackFrameRendered;
    private boolean exitWasDown;
    private boolean soundPaused;
    private int sampleTicks;
    private long enabledAtNanos;
    private long sampleStartedNanos;
    private long lastMaintenanceNanos;
    private double measuredClientTps;
    private Method clearParticlesMethod;
    private volatile JFrame statusFrame;
    private volatile JLabel statusLabel;

    public BlackoutModule() {
        super("Blackout", Category.RENDER, ModuleMode.TOGGLE);
        statusWindow = setting("StatusWindow", ModuleSetting.bool("StatusWindow", false)
            .describe("Shows a lightweight external status panel refreshed once per second."));
    }

    @Override
    public void onEnable(Minecraft client) {
        if (client == null || client.getWindow() == null) {
            return;
        }
        previousNoRender = client.noRender;
        previousPauseOnLostFocus = client.options != null && client.options.pauseOnLostFocus;
        stateCaptured = true;
        rendererSuppressed = false;
        blackFrameRendered = false;
        soundPaused = false;
        sampleTicks = 0;
        enabledAtNanos = System.nanoTime();
        sampleStartedNanos = enabledAtNanos;
        lastMaintenanceNanos = enabledAtNanos;
        measuredClientTps = 0.0D;
        exitWasDown = exitKeyDown(client);
        if (client.options != null) {
            client.options.pauseOnLostFocus = false;
        }
        client.noRender = false;
        client.setScreen(new BlackoutArmingScreen(this));
        BootstrapLog.info("Blackout arming: waiting for a presented black frame, emergencyExit=F12");
    }

    @Override
    public void onDisable(Minecraft client) {
        if (client != null) {
            client.noRender = stateCaptured && previousNoRender;
            if (client.options != null && stateCaptured) {
                client.options.pauseOnLostFocus = previousPauseOnLostFocus;
            }
            if (soundPaused && client.getSoundManager() != null) {
                client.getSoundManager().resume();
            }
            if (client.screen instanceof BlackoutArmingScreen) {
                client.setScreen(null);
            }
            client.updateTitle();
        }
        closeStatusWindow();
        BootstrapLog.info("Blackout disabled: elapsed=" + elapsedText()
            + ", clientTps=" + String.format(Locale.ROOT, "%.1f", measuredClientTps));
        stateCaptured = false;
        rendererSuppressed = false;
        blackFrameRendered = false;
        soundPaused = false;
        exitWasDown = false;
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.getWindow() == null) {
            return;
        }
        boolean exitDown = exitKeyDown(client);
        if (exitDown && !exitWasDown) {
            setEnabled(client, false);
            XenobyteModernClient.MODULES.saveConfig();
            return;
        }
        exitWasDown = exitDown;
        updateTickRate();

        if (!rendererSuppressed) {
            // ClientSpeed can execute many ticks before one frame. Wait for the arming screen itself
            // to render so the last visible frame is guaranteed to be black.
            if (!blackFrameRendered) {
                return;
            }
            if (client.screen instanceof BlackoutArmingScreen) {
                client.setScreen(null);
            }
            suppressRenderer(client);
            return;
        }

        // Keep this module last in registration order so temporary hidden menus cannot re-enable rendering.
        client.noRender = true;
        rendererSuppressed = true;
        long now = System.nanoTime();
        if (now - lastMaintenanceNanos >= MAINTENANCE_INTERVAL_NANOS) {
            lastMaintenanceNanos = now;
            clearParticles(client);
            pauseSounds(client);
            updateWindowTitle(client);
            updateStatusWindow(client);
        }
    }

    private void suppressRenderer(Minecraft client) {
        clearParticles(client);
        pauseSounds(client);
        client.noRender = true;
        rendererSuppressed = true;
        lastMaintenanceNanos = System.nanoTime();
        updateWindowTitle(client);
        openStatusWindow();
        updateStatusWindow(client);
        BootstrapLog.info("Blackout active: renderer=OFF, sounds=PAUSED, particles=CLEARED, statusWindow="
            + statusWindow.displayValue() + ", emergencyExit=F12");
    }

    private void pauseSounds(Minecraft client) {
        if (client.getSoundManager() == null) {
            return;
        }
        client.getSoundManager().pause();
        soundPaused = true;
    }

    private void clearParticles(Minecraft client) {
        if (client.particleEngine == null) {
            return;
        }
        try {
            if (clearParticlesMethod == null) {
                for (String methodName : new String[] {"clearParticles", "m_107319_", "f"}) {
                    try {
                        clearParticlesMethod = client.particleEngine.getClass().getDeclaredMethod(methodName);
                        clearParticlesMethod.setAccessible(true);
                        break;
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // Try the next mapping name.
                    }
                }
            }
            if (clearParticlesMethod != null) {
                clearParticlesMethod.invoke(client.particleEngine);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            clearParticlesMethod = null;
            BootstrapLog.error("Blackout particle cleanup failed", error);
        }
    }

    private void updateTickRate() {
        sampleTicks++;
        long now = System.nanoTime();
        long elapsed = now - sampleStartedNanos;
        if (elapsed < 1_000_000_000L) {
            return;
        }
        measuredClientTps = sampleTicks * 1_000_000_000.0D / elapsed;
        sampleTicks = 0;
        sampleStartedNanos = now;
    }

    private void updateWindowTitle(Minecraft client) {
        client.getWindow().setTitle("BLACKOUT | " + elapsedText()
            + " | client " + String.format(Locale.ROOT, "%.1f", measuredClientTps) + " t/s"
            + " | " + positionText(client) + " | " + workerModules() + " | F12 EXIT");
    }

    private void openStatusWindow() {
        if (!statusWindow.boolValue() || GraphicsEnvironment.isHeadless()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!enabled() || !rendererSuppressed || statusFrame != null) {
                return;
            }
            JFrame frame = new JFrame("Xenobyte Blackout");
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.setAlwaysOnTop(true);
            frame.setAutoRequestFocus(false);
            frame.setMinimumSize(new Dimension(390, 225));

            JPanel panel = new JPanel(new BorderLayout(0, 14));
            panel.setBackground(Color.BLACK);
            panel.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));

            JLabel title = new JLabel("BLACKOUT ACTIVE", SwingConstants.CENTER);
            title.setForeground(Color.WHITE);
            title.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
            panel.add(title, BorderLayout.NORTH);

            JLabel liveStatus = new JLabel("Waiting for first status sample...", SwingConstants.CENTER);
            liveStatus.setForeground(new Color(176, 206, 230));
            liveStatus.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
            panel.add(liveStatus, BorderLayout.CENTER);

            JButton exit = new JButton("EXIT BLACKOUT (F12)");
            exit.setFocusable(false);
            exit.addActionListener(event -> {
                Minecraft runningClient = Minecraft.getInstance();
                runningClient.execute(() -> {
                    if (enabled()) {
                        setEnabled(runningClient, false);
                        XenobyteModernClient.MODULES.saveConfig();
                    }
                });
            });
            panel.add(exit, BorderLayout.SOUTH);

            frame.setContentPane(panel);
            frame.pack();
            frame.setLocation(24, 24);
            statusLabel = liveStatus;
            statusFrame = frame;
            frame.setVisible(true);
        });
    }

    private void updateStatusWindow(Minecraft client) {
        if (!statusWindow.boolValue()) {
            return;
        }
        JLabel label = statusLabel;
        if (label == null) {
            return;
        }
        String text = "<html><div style='text-align:center'>"
            + "Renderer FPS: 0 (disabled)<br>"
            + "Client ticks/s: " + String.format(Locale.ROOT, "%.1f", measuredClientTps) + "<br>"
            + "Uptime: " + elapsedText() + "<br>"
            + "Position: " + positionText(client) + "<br>"
            + "Workers: " + workerModules()
            + "</div></html>";
        SwingUtilities.invokeLater(() -> {
            if (label == statusLabel) {
                label.setText(text);
            }
        });
    }

    private void closeStatusWindow() {
        JFrame frame = statusFrame;
        statusFrame = null;
        statusLabel = null;
        if (frame != null) {
            SwingUtilities.invokeLater(frame::dispose);
        }
    }

    private String positionText(Minecraft client) {
        return client.player == null ? "no-player" : String.format(Locale.ROOT, "%.0f/%.0f/%.0f",
            client.player.getX(), client.player.getY(), client.player.getZ());
    }

    private String workerModules() {
        String workers = XenobyteModernClient.MODULES.modules().stream()
            .filter(module -> module != this && module.enabled() && module.category() != Category.RENDER)
            .map(XenoModule::name)
            .limit(4)
            .collect(Collectors.joining(","));
        return workers.isBlank() ? "none" : workers;
    }

    private boolean exitKeyDown(Minecraft client) {
        return client != null && client.getWindow() != null
            && GLFW.glfwGetKey(client.getWindow().getWindow(), EXIT_KEY) == GLFW.GLFW_PRESS;
    }

    private String elapsedText() {
        if (enabledAtNanos <= 0L) {
            return "00:00";
        }
        long totalSeconds = Math.max(0L, (System.nanoTime() - enabledAtNanos) / 1_000_000_000L);
        long hours = totalSeconds / 3_600L;
        long minutes = totalSeconds / 60L % 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0L
            ? String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
            : String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    @Override
    public String description() {
        return "Stops the entire Minecraft renderer while client ticks, networking and background modules continue. F12 always exits.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "Blackout renderer=" + rendererSuppressed
            + " blackFrameRendered=" + blackFrameRendered
            + " elapsed=" + elapsedText()
            + " clientTps=" + String.format(Locale.ROOT, "%.1f", measuredClientTps)
            + " soundsPaused=" + soundPaused
            + " statusWindow=" + statusWindow.displayValue()
            + " statusOpen=" + (statusFrame != null)
            + " exit=F12";
    }

    private static final class BlackoutArmingScreen extends Screen {
        private final BlackoutModule owner;

        private BlackoutArmingScreen(BlackoutModule owner) {
            super(Component.literal("Blackout"));
            this.owner = owner;
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public void render(GuiGraphics context, int mouseX, int mouseY, float partialTick) {
            context.fill(0, 0, width, height, 0xff000000);
            context.drawCenteredString(font, "BLACKOUT", width / 2, height / 2 - 18, 0xffffffff);
            context.drawCenteredString(font, "Renderer disabled; background modules remain active", width / 2, height / 2, 0xff9fb4c9);
            context.drawCenteredString(font, "Press F12 to exit", width / 2, height / 2 + 18, 0xffffd65a);
            if (!owner.blackFrameRendered) {
                owner.blackFrameRendered = true;
                BootstrapLog.info("Blackout black frame rendered: renderer suppression queued for the next client tick");
            }
        }
    }
}
