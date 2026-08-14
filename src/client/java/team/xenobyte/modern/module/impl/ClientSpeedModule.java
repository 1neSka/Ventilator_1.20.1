package team.xenobyte.modern.module.impl;

import java.lang.reflect.Field;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Timer;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class ClientSpeedModule extends XenoModule {
    private static final float DEFAULT_MS_PER_TICK = 50.0F;
    private static final long LOG_INTERVAL_NANOS = 2_000_000_000L;

    private final ModuleSetting multiplier = setting("Multiplier",
        ModuleSetting.number("Multiplier", 2.0D, 0.5D, 40.0D, 0.5D)
            .describe("Client timer multiplier. 1.0 = normal, 0.5 = slow, 40.0 = very fast."));

    private float originalMsPerTick = DEFAULT_MS_PER_TICK;
    private boolean applied;
    private Field timerField;
    private Field msPerTickField;
    private long lastLogNanos;

    public ClientSpeedModule() {
        super("ClientSpeed", Category.MISC, ModuleMode.TOGGLE);
        timerField = findField(Minecraft.class, Timer.class, "timer", "f_91013_", "R");
        msPerTickField = findField(Timer.class, float.class, "msPerTick", "f_92521_", "d");
    }

    private Timer getTimer(Minecraft client) {
        if (client == null || timerField == null) {
            return null;
        }
        try {
            return (Timer) timerField.get(client);
        } catch (IllegalAccessException | IllegalArgumentException error) {
            BootstrapLog.info("ClientSpeed timer get failed: " + error.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public void onEnable(Minecraft client) {
        Timer timer = getTimer(client);
        if (timer != null) {
            originalMsPerTick = getMsPerTick(timer, DEFAULT_MS_PER_TICK);
        }
        BootstrapLog.info("ClientSpeed enabled: originalMsPerTick=" + format(originalMsPerTick)
            + ", multiplier=" + multiplier.displayValue()
            + ", timerField=" + fieldName(timerField)
            + ", msPerTickField=" + fieldName(msPerTickField));
        applied = false;
    }

    @Override
    public void onDisable(Minecraft client) {
        Timer timer = getTimer(client);
        if (timer != null && applied) {
            setMsPerTick(timer, originalMsPerTick);
            applied = false;
        }
        BootstrapLog.info("ClientSpeed disabled: restoredMsPerTick=" + format(originalMsPerTick));
    }

    @Override
    public void onTick(Minecraft client) {
        Timer timer = getTimer(client);
        if (timer == null || msPerTickField == null) {
            log("ClientSpeed unavailable: timer=" + (timer != null) + ", msPerTickField=" + fieldName(msPerTickField));
            return;
        }

        float speed = Math.max(0.05F, multiplier.floatValue());
        float targetMsPerTick = originalMsPerTick / speed;
        if (Math.abs(speed - 1.0F) > 0.0001F) {
            if (setMsPerTick(timer, targetMsPerTick)) {
                log("ClientSpeed applied: multiplier=" + format(speed)
                    + ", msPerTick=" + format(targetMsPerTick)
                    + ", actual=" + format(getMsPerTick(timer, -1.0F)));
            }
            applied = true;
        } else if (applied) {
            setMsPerTick(timer, originalMsPerTick);
            applied = false;
            log("ClientSpeed restored during tick: msPerTick=" + format(originalMsPerTick));
        }
    }

    @Override
    public String description() {
        return "Changes Minecraft client timer speed by rewriting Timer.msPerTick.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        Timer timer = getTimer(client);
        float current = timer == null ? -1.0F : getMsPerTick(timer, -1.0F);
        return "ClientSpeed x" + multiplier.displayValue() + " ms=" + format(current);
    }

    private Field findField(Class<?> owner, Class<?> type, String... names) {
        for (String name : names) {
            try {
                Field field = owner.getDeclaredField(name);
                if (type == null || field.getType() == type) {
                    field.setAccessible(true);
                    return field;
                }
            } catch (NoSuchFieldException ignored) {
                // Try the next known mapping name.
            }
        }

        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() == type) {
                field.setAccessible(true);
                BootstrapLog.info("ClientSpeed fallback field resolved: owner=" + owner.getName()
                    + ", field=" + field.getName()
                    + ", type=" + type.getName());
                return field;
            }
        }

        BootstrapLog.info("ClientSpeed field not found: owner=" + owner.getName()
            + ", type=" + (type == null ? "any" : type.getName())
            + ", names=" + String.join("/", names));
        return null;
    }

    private float getMsPerTick(Timer timer, float fallback) {
        if (timer == null || msPerTickField == null) {
            return fallback;
        }
        try {
            return msPerTickField.getFloat(timer);
        } catch (IllegalAccessException | IllegalArgumentException error) {
            BootstrapLog.info("ClientSpeed msPerTick get failed: " + error.getClass().getSimpleName());
            return fallback;
        }
    }

    private boolean setMsPerTick(Timer timer, float value) {
        if (timer == null || msPerTickField == null) {
            return false;
        }
        try {
            msPerTickField.setFloat(timer, value);
            return true;
        } catch (IllegalAccessException | IllegalArgumentException error) {
            BootstrapLog.info("ClientSpeed msPerTick set failed: " + error.getClass().getSimpleName()
                + ", value=" + format(value));
            return false;
        }
    }

    private void log(String message) {
        long now = System.nanoTime();
        if (now - lastLogNanos < LOG_INTERVAL_NANOS) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info(message);
    }

    private String format(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String fieldName(Field field) {
        return field == null ? "null" : field.getName();
    }
}
