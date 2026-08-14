package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;
import team.xenobyte.modern.util.ReflectionField;

public class FastPlaceModule extends XenoModule {
    private static final ReflectionField RIGHT_CLICK_DELAY = new ReflectionField(Minecraft.class, "rightClickDelay", "f_91011_", "aQ");

    private final ModuleSetting delay = setting("Delay", ModuleSetting.number("Delay", 0.0D, 0.0D, 4.0D, 1.0D)
        .describe("Client right-click delay to keep while enabled. Zero matches the original FastPlace behavior."));

    public FastPlaceModule() {
        super("FastPlace", Category.WORLD, ModuleMode.TOGGLE);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client != null && client.player != null && client.level != null) {
            RIGHT_CLICK_DELAY.setInt(client, delay.intValue());
        }
    }

    @Override
    public String description() {
        return "Keeps the local right-click delay low for faster block placement and item use.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "FastPlace delay=" + delay.displayValue();
    }
}
