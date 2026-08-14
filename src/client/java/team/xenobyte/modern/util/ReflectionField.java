package team.xenobyte.modern.util;

import java.lang.reflect.Field;

import team.xenobyte.modern.bootstrap.BootstrapLog;

public final class ReflectionField {
    private final Class<?> owner;
    private final String[] names;
    private Field field;
    private boolean failed;

    public ReflectionField(Class<?> owner, String... names) {
        this.owner = owner;
        this.names = names.clone();
    }

    public boolean setInt(Object target, int value) {
        Field resolved = resolve();
        if (resolved == null) {
            return false;
        }
        try {
            resolved.setInt(target, value);
            return true;
        } catch (IllegalAccessException | IllegalArgumentException error) {
            BootstrapLog.info("Reflection int set failed: owner=" + owner.getName() + ", field=" + resolved.getName() + ", error=" + error.getClass().getSimpleName());
            return false;
        }
    }

    public boolean setFloat(Object target, float value) {
        Field resolved = resolve();
        if (resolved == null) {
            return false;
        }
        try {
            resolved.setFloat(target, value);
            return true;
        } catch (IllegalAccessException | IllegalArgumentException error) {
            BootstrapLog.info("Reflection float set failed: owner=" + owner.getName() + ", field=" + resolved.getName() + ", error=" + error.getClass().getSimpleName());
            return false;
        }
    }

    public float getFloat(Object target, float fallback) {
        Field resolved = resolve();
        if (resolved == null) {
            return fallback;
        }
        try {
            return resolved.getFloat(target);
        } catch (IllegalAccessException | IllegalArgumentException error) {
            BootstrapLog.info("Reflection float get failed: owner=" + owner.getName() + ", field=" + resolved.getName() + ", error=" + error.getClass().getSimpleName());
            return fallback;
        }
    }

    private Field resolve() {
        if (field != null) {
            return field;
        }
        if (failed) {
            return null;
        }
        for (String name : names) {
            try {
                Field candidate = owner.getDeclaredField(name);
                candidate.setAccessible(true);
                field = candidate;
                return field;
            } catch (NoSuchFieldException ignored) {
                // Try the next known mapping name.
            }
        }
        failed = true;
        BootstrapLog.info("Reflection field not found: owner=" + owner.getName() + ", names=" + String.join("/", names));
        return null;
    }
}
