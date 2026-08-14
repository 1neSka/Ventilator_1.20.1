package team.xenobyte.modern.module.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class TextRadarModule extends XenoModule {
    private final List<String> lines = new ArrayList<>();
    private final ModuleSetting range = setting("Range", ModuleSetting.number("Range", 200.0D, 32.0D, 512.0D, 16.0D)
        .describe("Maximum player distance for TextRadar."));
    private final ModuleSetting limit = setting("Limit", ModuleSetting.number("Limit", 10.0D, 1.0D, 20.0D, 1.0D)
        .describe("Maximum number of player lines shown."));
    private final ModuleSetting showHp = setting("ShowHP", ModuleSetting.bool("ShowHP", true)
        .describe("Adds current player health to TextRadar lines."));
    private final ModuleSetting showHand = setting("ShowHand", ModuleSetting.bool("ShowHand", true)
        .describe("Adds the player's main-hand item to TextRadar lines when available."));

    public TextRadarModule() {
        super("TextRadar", Category.MISC, ModuleMode.TOGGLE);
    }

    @Override
    public void onTick(Minecraft client) {
        lines.clear();
        if (client.level == null || client.player == null) {
            return;
        }
        double rangeValue = range.doubleValue();
        client.level.players().stream()
            .filter(player -> player != client.player)
            .filter(player -> player.distanceToSqr(client.player) <= rangeValue * rangeValue)
            .sorted(Comparator.comparingDouble(player -> player.distanceToSqr(client.player)))
            .limit(limit.intValue())
            .forEach(player -> lines.add(format(client, player)));
        if (lines.isEmpty()) {
            lines.add("TextRadar: nobody");
        }
    }

    @Override
    public void onHudRender(GuiGraphics context, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        int maxWidth = lines.stream().mapToInt(client.font::width).max().orElse(0);
        int x = Math.max(8, client.getWindow().getGuiScaledWidth() - maxWidth - 8);
        int y = Math.max(8, client.getWindow().getGuiScaledHeight() - lines.size() * 10 - 8);
        for (String line : lines) {
            context.drawString(client.font, line, x, y, 0xffd7e1ec);
            y += 10;
        }
    }

    @Override
    public HudBounds hudBounds(Minecraft client) {
        if (client == null || client.font == null || client.getWindow() == null) {
            return null;
        }
        int maxWidth = Math.max(100, lines.stream().mapToInt(client.font::width).max().orElse(0));
        int height = Math.max(10, lines.size() * 10);
        int x = Math.max(8, client.getWindow().getGuiScaledWidth() - maxWidth - 8);
        int y = Math.max(8, client.getWindow().getGuiScaledHeight() - height - 8);
        return new HudBounds(x, y, maxWidth, height);
    }

    private String format(Minecraft client, Player player) {
        int distance = (int)Math.sqrt(player.distanceToSqr(client.player));
        StringBuilder builder = new StringBuilder("[")
            .append(player.getGameProfile().getName())
            .append(" ")
            .append(distance)
            .append("m");
        if (showHp.boolValue()) {
            builder.append(" ")
                .append(Math.round(player.getHealth()))
                .append("/")
                .append(Math.round(player.getMaxHealth()))
                .append("hp");
        }
        if (showHand.boolValue()) {
            ItemStack stack = player.getMainHandItem();
            if (!stack.isEmpty()) {
                String item = stack.getHoverName().getString();
                if (item.length() > 18) {
                    item = item.substring(0, 18);
                }
                builder.append(" ").append(item);
            }
        }
        return builder.append("]").toString();
    }

    @Override
    public String description() {
        return "Displays nearby players and distances as HUD text.";
    }
}
