package team.xenobyte.modern.module.impl;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class AdvancedTooltipModule extends XenoModule {
    private final ModuleSetting registry = setting("Registry", ModuleSetting.bool("Registry", true)
        .describe("Adds the item registry id and numeric registry id."));
    private final ModuleSetting damage = setting("Damage", ModuleSetting.bool("Damage", true)
        .describe("Adds damage/durability metadata for damageable items."));
    private final ModuleSetting nbt = setting("NBT", ModuleSetting.bool("NBT", false)
        .describe("Adds compact NBT data when the stack has a tag."));

    public AdvancedTooltipModule() {
        super("AdvancedTooltip", Category.MISC, ModuleMode.TOGGLE);
    }

    @Override
    public void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack == null || stack.isEmpty()) {
            return;
        }

        if (registry.boolValue()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            int numeric = BuiltInRegistries.ITEM.getId(stack.getItem());
            event.getToolTip().add(Component.literal(id + " " + numeric + ":" + stack.getDamageValue()).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (damage.boolValue() && stack.isDamageableItem()) {
            event.getToolTip().add(Component.literal("damage " + stack.getDamageValue() + "/" + stack.getMaxDamage()).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (nbt.boolValue() && stack.hasTag()) {
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                event.getToolTip().add(Component.literal("nbt " + tag).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }

    @Override
    public String description() {
        return "Adds registry id, numeric id, damage, and optional NBT metadata to item tooltips.";
    }

    @Override
    public String runtimeInfo(net.minecraft.client.Minecraft client) {
        return "AdvancedTooltip registry=" + registry.displayValue()
            + " damage=" + damage.displayValue()
            + " nbt=" + nbt.displayValue();
    }
}
