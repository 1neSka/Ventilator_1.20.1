package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import team.xenobyte.modern.XenobyteModernClient;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleManager;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;

public class PanicModule extends XenoModule {
    private final ModuleManager modules;

    public PanicModule(ModuleManager modules) {
        super("Panic", Category.MISC, ModuleMode.SINGLE);
        this.modules = modules;
    }

    @Override
    public void onPerform(Minecraft client) {
        XenobyteModernClient.shutdown(client, "panic-module");
    }

    @Override
    public String description() {
        return "Disables all runtime callbacks until Minecraft restarts.";
    }
}
