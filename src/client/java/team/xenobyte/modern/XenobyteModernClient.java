package team.xenobyte.modern;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderBlockScreenEffectEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.Mod;
import team.xenobyte.modern.bootstrap.BootstrapHealth;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.bootstrap.RuntimeEnvironment;
import team.xenobyte.modern.gui.XenoScreen;
import team.xenobyte.modern.module.ModuleManager;
import team.xenobyte.modern.module.impl.AdvancedTooltipModule;
import team.xenobyte.modern.module.impl.AirJumpsModule;
import team.xenobyte.modern.module.impl.AreaMinerModule;
import team.xenobyte.modern.module.impl.AutoEatModule;
import team.xenobyte.modern.module.impl.AutoSpawnModule;
import team.xenobyte.modern.module.impl.AutoTotemModule;
import team.xenobyte.modern.module.impl.BlockOverlayModule;
import team.xenobyte.modern.module.impl.BlackoutModule;
import team.xenobyte.modern.module.impl.BuilderModule;
import team.xenobyte.modern.module.impl.CeilingModule;
import team.xenobyte.modern.module.impl.CheckVanishModule;
import team.xenobyte.modern.module.impl.ClickerModule;
import team.xenobyte.modern.module.impl.DpsMeterModule;
import team.xenobyte.modern.module.impl.EspModule;
import team.xenobyte.modern.module.impl.FastBreakModule;
import team.xenobyte.modern.module.impl.FastPlaceModule;
import team.xenobyte.modern.module.impl.FlyModule;
import team.xenobyte.modern.module.impl.FreeCamModule;
import team.xenobyte.modern.module.impl.HealthLimiterModule;
import team.xenobyte.modern.module.impl.LootChestModule;
import team.xenobyte.modern.module.impl.NoFallModule;
import team.xenobyte.modern.module.impl.NoRainModule;
import team.xenobyte.modern.module.impl.NoSlowModule;
import team.xenobyte.modern.module.impl.OverlayModule;
import team.xenobyte.modern.module.impl.PanicModule;
import team.xenobyte.modern.module.impl.RuntimeInfoModule;
import team.xenobyte.modern.module.impl.ScaffoldModule;
import team.xenobyte.modern.module.impl.SearchItemModule;
import team.xenobyte.modern.module.impl.SelectZoneModule;
import team.xenobyte.modern.module.impl.SoundsModule;
import team.xenobyte.modern.module.impl.TextRadarModule;
import team.xenobyte.modern.module.impl.VanilaNukerModule;
import team.xenobyte.modern.module.impl.VelocityModule;
import team.xenobyte.modern.module.impl.VisionModule;
import team.xenobyte.modern.module.impl.VozduhModule;
import team.xenobyte.modern.module.impl.XRayModule;
import team.xenobyte.modern.module.impl.XRaySelectModule;
import team.xenobyte.modern.module.impl.XRayTargetRegistry;
import team.xenobyte.modern.render.WorldRenderContext;
import team.xenobyte.modern.module.impl.KillAuraModule;
import team.xenobyte.modern.module.impl.LoggerModule;
import team.xenobyte.modern.module.impl.AutoMinerModule;
import team.xenobyte.modern.module.impl.ClientSpeedModule;
import team.xenobyte.modern.module.impl.KubeJSSpammer;


@Mod(XenobyteModernClient.MOD_ID)
public class XenobyteModernClient {
    public static final String MOD_ID = "xenobyte_modern";
    private static final String ACTIVE_GENERATION_PROPERTY = "xenobyte-modern.activeGeneration";
    private static final long FALLBACK_TICK_GRACE_NANOS = 500_000_000L;
    public static final ModuleManager MODULES = new ModuleManager();

    private static final ForgeEventBridge EVENT_BRIDGE = new ForgeEventBridge();
    private static boolean initialized;
    private static volatile boolean runtimeActive;
    private static String runtimeGeneration;
    private static boolean rawGuiKeyDown;
    private static boolean rawOpenLogged;
    private static boolean fallbackPollerStarted;
    private static volatile long lastForgeTickNanos;

    public XenobyteModernClient() {
        bootstrap("forge-mod");
    }

    public static synchronized void bootstrap(String source) {
        if (initialized) {
            BootstrapLog.info("Bootstrap already initialized, source=" + source);
            return;
        }

        BootstrapLog.info("Starting Forge bootstrap, source=" + source + ", thread=" + Thread.currentThread().getName());

        try {
            RuntimeEnvironment.require(source);

            runtimeGeneration = createRuntimeGeneration();
            System.setProperty(ACTIVE_GENERATION_PROPERTY, runtimeGeneration);
            runtimeActive = true;
            BootstrapLog.info("Runtime generation activated: " + runtimeGeneration);

            XRayTargetRegistry.ensureLoaded();
            registerModule(new XRayModule());
            registerModule(new XRaySelectModule());
            registerModule(new SelectZoneModule());
            registerModule(new EspModule());
            registerModule(new BlockOverlayModule());
            registerModule(new NoRainModule());
            registerModule(new DpsMeterModule());
            registerModule(new TextRadarModule());
            registerModule(new AdvancedTooltipModule());
            registerModule(new VozduhModule(MODULES));
            registerModule(new ClickerModule());
            registerModule(new FastBreakModule());
            registerModule(new FastPlaceModule());
            registerModule(new LootChestModule());
            registerModule(new ScaffoldModule());
            registerModule(new BuilderModule());
            registerModule(new VanilaNukerModule());
            registerModule(new AreaMinerModule());
            registerModule(new CeilingModule());
            registerModule(new FlyModule());
            registerModule(new VelocityModule());
            registerModule(new FreeCamModule());
            registerModule(new NoSlowModule());
            registerModule(new VisionModule());
            registerModule(new OverlayModule());
            registerModule(new NoFallModule());
            registerModule(new AirJumpsModule());
            registerModule(new RuntimeInfoModule());
            registerModule(new AutoSpawnModule());
            registerModule(new AutoTotemModule());
            registerModule(new AutoEatModule());
            registerModule(new HealthLimiterModule());
            registerModule(new SearchItemModule());
            registerModule(new CheckVanishModule());
            registerModule(new SoundsModule());
            registerModule(new LoggerModule());
            registerModule(new PanicModule(MODULES));
            registerModule(new KillAuraModule());
            registerModule(new AutoMinerModule());
            registerModule(new ClientSpeedModule());
            registerModule(new KubeJSSpammer());
            // Keep Blackout last: it reasserts noRender after modules that temporarily open hidden menus.
            registerModule(new BlackoutModule());
            MODULES.loadConfig();
            BootstrapLog.info("Module phase completed: " + MODULES.summary());

            MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> EVENT_BRIDGE.onClientTick(event));
            MinecraftForge.EVENT_BUS.addListener((ItemTooltipEvent event) -> EVENT_BRIDGE.onItemTooltip(event));
            MinecraftForge.EVENT_BUS.addListener((MovementInputUpdateEvent event) -> EVENT_BRIDGE.onMovementInput(event));
            MinecraftForge.EVENT_BUS.addListener((RenderBlockScreenEffectEvent event) -> EVENT_BRIDGE.onRenderBlockScreenEffect(event));
            MinecraftForge.EVENT_BUS.addListener((RenderGuiOverlayEvent.Pre event) -> EVENT_BRIDGE.onRenderGuiPre(event));
            MinecraftForge.EVENT_BUS.addListener((RenderGuiOverlayEvent.Post event) -> EVENT_BRIDGE.onRenderGui(event));
            MinecraftForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> EVENT_BRIDGE.onRenderLevelStage(event));
            BootstrapLog.info("Forge direct event listeners registered");

            startFallbackPoller();

            initialized = true;
            BootstrapLog.info("Bootstrap completed: " + status());
        } catch (RuntimeException | LinkageError error) {
            BootstrapLog.error("Bootstrap failed", error);
            throw error;
        }
    }

    public static synchronized String status() {
        return "initialized=" + initialized
            + ", runtimeActive=" + runtimeActive
            + ", runtimeGeneration=" + runtimeGeneration
            + ", activeGeneration=" + System.getProperty(ACTIVE_GENERATION_PROPERTY, "")
            + ", rawGuiKeyDown=" + rawGuiKeyDown
            + ", rawOpenLogged=" + rawOpenLogged
            + ", fallbackPollerStarted=" + fallbackPollerStarted
            + ", " + MODULES.summary()
            + ", " + BootstrapHealth.summary();
    }

    public static synchronized void shutdown(Minecraft client, String source) {
        if (!initialized || !runtimeActive) {
            BootstrapLog.info("Shutdown ignored, source=" + source + ", status=" + status());
            return;
        }

        BootstrapLog.info("Runtime shutdown requested, source=" + source);
        runtimeActive = false;
        if (runtimeGeneration != null && runtimeGeneration.equals(System.getProperty(ACTIVE_GENERATION_PROPERTY))) {
            System.clearProperty(ACTIVE_GENERATION_PROPERTY);
        }
        rawGuiKeyDown = false;
        MODULES.saveConfig();
        MODULES.panic(client);
        BootstrapLog.info("Runtime shutdown completed: " + status());
    }

    private static String createRuntimeGeneration() {
        return Long.toHexString(System.currentTimeMillis())
            + "-"
            + Long.toHexString(System.nanoTime())
            + "-"
            + Integer.toHexString(System.identityHashCode(XenobyteModernClient.class.getClassLoader()));
    }

    private static boolean isCurrentRuntime() {
        if (!runtimeActive || runtimeGeneration == null) {
            return false;
        }

        String activeGeneration = System.getProperty(ACTIVE_GENERATION_PROPERTY, "");
        boolean current = runtimeGeneration.equals(activeGeneration);
        if (!current) {
            runtimeActive = false;
        }
        return current;
    }

    private static void registerModule(team.xenobyte.modern.module.XenoModule module) {
        boolean added = MODULES.register(module);
        BootstrapLog.info((added ? "Registered module " : "Skipped duplicate module ") + module.name());
    }

    private static void pollRawGuiKey(Minecraft client) {
        if (!isCurrentRuntime() || client == null || client.getWindow() == null) {
            return;
        }

        boolean down = GLFW.glfwGetKey(client.getWindow().getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (down && !rawGuiKeyDown) {
            openGui(client, "raw-glfw");
        }
        rawGuiKeyDown = down;
    }

    private static void openGui(Minecraft client, String source) {
        if (!isCurrentRuntime() || client == null || MODULES.isPanicked() || client.screen instanceof XenoScreen) {
            return;
        }

        Screen returnScreen = client.screen instanceof XenoScreen ? null : client.screen;
        client.setScreen(new XenoScreen(MODULES, returnScreen));
        if ("raw-glfw".equals(source) && !rawOpenLogged) {
            rawOpenLogged = true;
            BootstrapLog.info("GUI opened via raw GLFW fallback path");
        }
    }

    private static void runClientTick(Minecraft client, String source) {
        BootstrapHealth.markTick(client, source);
        pollRawGuiKey(client);
        MODULES.tick(client);
    }

    private static boolean shouldRunFallbackTick() {
        long lastForgeTick = lastForgeTickNanos;
        return lastForgeTick == 0L || System.nanoTime() - lastForgeTick > FALLBACK_TICK_GRACE_NANOS;
    }

    private static void startFallbackPoller() {
        if (fallbackPollerStarted) {
            return;
        }
        fallbackPollerStarted = true;

        Thread thread = new Thread(() -> {
            BootstrapLog.info("Fallback poller started");
            while (isCurrentRuntime()) {
                try {
                    Minecraft client = Minecraft.getInstance();
                    if (client != null) {
                        client.execute(() -> {
                            if (!isCurrentRuntime()) {
                                return;
                            }
                            if (shouldRunFallbackTick()) {
                                runClientTick(client, "fallback");
                            }
                        });
                    }
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    BootstrapLog.info("Fallback poller interrupted");
                    return;
                } catch (Throwable throwable) {
                    BootstrapLog.error("Fallback poller failure", throwable);
                    return;
                }
            }
            BootstrapLog.info("Fallback poller stopped; current=" + isCurrentRuntime() + ", status=" + status());
        }, "xenobyte-modern-fallback");
        thread.setDaemon(true);
        thread.start();
    }

    public static final class ForgeEventBridge {
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (!isCurrentRuntime()) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            if (event.phase == TickEvent.Phase.START) {
                MODULES.tickStart(client);
                return;
            }
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            lastForgeTickNanos = System.nanoTime();
            runClientTick(client, "forge");
        }

        public void onRenderGuiPre(RenderGuiOverlayEvent.Pre event) {
            if (!isCurrentRuntime()) {
                return;
            }
            MODULES.beforeRender(Minecraft.getInstance());
        }

        public void onRenderGui(RenderGuiOverlayEvent.Post event) {
            if (!isCurrentRuntime()) {
                return;
            }
            BootstrapHealth.markHud(event.getGuiGraphics(), event.getPartialTick());
            MODULES.hud(event.getGuiGraphics(), event.getPartialTick());
        }

        public void onItemTooltip(ItemTooltipEvent event) {
            if (!isCurrentRuntime()) {
                return;
            }
            MODULES.itemTooltip(event);
        }

        public void onMovementInput(MovementInputUpdateEvent event) {
            if (!isCurrentRuntime()) {
                return;
            }
            MODULES.movementInput(event);
        }

        public void onRenderBlockScreenEffect(RenderBlockScreenEffectEvent event) {
            if (!isCurrentRuntime()) {
                return;
            }
            MODULES.renderBlockScreenEffect(event);
        }

        public void onRenderLevelStage(RenderLevelStageEvent event) {
            if (!isCurrentRuntime()) {
                return;
            }
            MODULES.beforeRender(Minecraft.getInstance());
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
                return;
            }

            WorldRenderContext context = new WorldRenderContext(event.getPoseStack(), event.getCamera(), event.getPartialTick());
            BootstrapHealth.markWorld(context);
            MODULES.world(context);
        }
    }
}
