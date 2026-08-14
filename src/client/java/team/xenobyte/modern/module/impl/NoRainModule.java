package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;

public class NoRainModule extends XenoModule {
    public NoRainModule() {
        super("NoRain", Category.RENDER, ModuleMode.TOGGLE);
    }

    @Override
    public void onClientTickStart(Minecraft client) {
        clearWeather(client);
    }

    @Override
    public void onTick(Minecraft client) {
        clearWeather(client);
    }

    @Override
    public void onBeforeRender(Minecraft client) {
        clearWeather(client);
    }

    private void clearWeather(Minecraft client) {
        if (client == null || client.level == null) {
            return;
        }
        client.level.setRainLevel(0.0F);
        client.level.setThunderLevel(0.0F);
        client.getSoundManager().stop(null, SoundSource.WEATHER);
    }

    @Override
    public String description() {
        return "Client-side clears rain, thunder visuals, and associated ambient weather state.";
    }
}
