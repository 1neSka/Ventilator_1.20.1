package team.xenobyte.modern.module.setting;

import java.util.Locale;

public class ModuleSetting {
    public enum Kind {
        BOOLEAN,
        DOUBLE,
        CHOICE,
        ACTION
    }

    private final String name;
    private final Kind kind;
    private final double min;
    private final double max;
    private final double step;
    private final String[] choices;
    private final Runnable action;
    private double value;
    private String description = "";

    public static ModuleSetting bool(String name, boolean value) {
        return new ModuleSetting(name, Kind.BOOLEAN, value ? 1.0D : 0.0D, 0.0D, 1.0D, 1.0D, new String[0], null);
    }

    public static ModuleSetting number(String name, double value, double min, double max, double step) {
        return new ModuleSetting(name, Kind.DOUBLE, value, min, max, step, new String[0], null);
    }

    public static ModuleSetting choice(String name, int value, String... choices) {
        if (choices == null || choices.length == 0) {
            throw new IllegalArgumentException("choice setting requires at least one choice");
        }
        return new ModuleSetting(name, Kind.CHOICE, value, 0.0D, choices.length - 1.0D, 1.0D, choices.clone(), null);
    }

    public static ModuleSetting action(String name, Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action setting requires a callback");
        }
        return new ModuleSetting(name, Kind.ACTION, 0.0D, 0.0D, 0.0D, 0.0D, new String[0], action);
    }

    private ModuleSetting(String name, Kind kind, double value, double min, double max, double step, String[] choices, Runnable action) {
        this.name = name;
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.step = step;
        this.choices = choices;
        this.action = action;
        this.value = clamp(value);
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    public boolean boolValue() {
        return value >= 0.5D;
    }

    public int intValue() {
        return (int)Math.round(value);
    }

    public float floatValue() {
        return (float)value;
    }

    public double doubleValue() {
        return value;
    }

    public String choiceValue() {
        if (kind != Kind.CHOICE || choices.length == 0) {
            return "";
        }
        return choices[Math.max(0, Math.min(choices.length - 1, intValue()))];
    }

    public ModuleSetting describe(String description) {
        this.description = description == null ? "" : description;
        return this;
    }

    public String description() {
        return description;
    }

    public void click(int button) {
        if (kind == Kind.ACTION) {
            action.run();
            return;
        }
        if (kind == Kind.BOOLEAN) {
            value = boolValue() ? 0.0D : 1.0D;
            return;
        }

        double direction = button == 1 ? -1.0D : 1.0D;
        adjust(direction);
    }

    public void adjust(double direction) {
        if (kind == Kind.ACTION) {
            return;
        }
        if (kind == Kind.BOOLEAN) {
            if (direction != 0.0D) {
                value = boolValue() ? 0.0D : 1.0D;
            }
            return;
        }
        if (kind == Kind.CHOICE) {
            if (direction == 0.0D || choices.length == 0) {
                return;
            }
            int next = intValue() + (direction > 0.0D ? 1 : -1);
            if (next < 0) {
                next = choices.length - 1;
            } else if (next >= choices.length) {
                next = 0;
            }
            value = next;
            return;
        }
        value = clamp(value + direction * step);
    }

    public void setFromString(String raw) {
        if (kind == Kind.ACTION) {
            return;
        }
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            if (kind == Kind.CHOICE) {
                for (int i = 0; i < choices.length; i++) {
                    if (choices[i].equalsIgnoreCase(raw)) {
                        value = i;
                        return;
                    }
                }
            }
            value = clamp(Double.parseDouble(raw));
        } catch (NumberFormatException ignored) {
            // Keep the default value if the saved config is stale or invalid.
        }
    }

    public String rawValueString() {
        if (kind == Kind.ACTION) {
            return "";
        }
        if (kind == Kind.CHOICE) {
            return choiceValue();
        }
        return Double.toString(value);
    }

    public String displayValue() {
        if (kind == Kind.ACTION) {
            return "RUN";
        }
        if (kind == Kind.BOOLEAN) {
            return boolValue() ? "ON" : "OFF";
        }
        if (kind == Kind.CHOICE) {
            return choiceValue();
        }
        if (Math.abs(value - Math.rint(value)) < 0.0001D) {
            return Integer.toString((int)Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private double clamp(double input) {
        return Math.max(min, Math.min(max, input));
    }
}
