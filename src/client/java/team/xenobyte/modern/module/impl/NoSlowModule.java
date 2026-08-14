package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class NoSlowModule extends XenoModule {
    private final ModuleSetting inputFix = setting("InputFix", ModuleSetting.bool("InputFix", true)
        .describe("Restores movement input while the player is using an item."));
    private final ModuleSetting keepSprint = setting("Sprint", ModuleSetting.bool("Sprint", true)
        .describe("Keeps sprinting while using food, bow, potions, shields, and similar items."));
    private final ModuleSetting strength = setting("Strength", ModuleSetting.number("Strength", 1.0D, 0.2D, 2.0D, 0.1D)
        .describe("Horizontal motion compensation while an item is being used."));

    private long lastLogNanos;

    public NoSlowModule() {
        super("NoSlow", Category.MOVE, ModuleMode.TOGGLE);
    }

    @Override
    public void onClientTickStart(Minecraft client) {
        apply(client);
    }

    @Override
    public void onTick(Minecraft client) {
        apply(client);
    }

    private void apply(Minecraft client) {
        if (client == null || client.player == null || client.options == null || client.screen != null || !client.player.isUsingItem()) {
            return;
        }

        if (inputFix.boolValue()) {
            restoreInput(client);
        }

        if (keepSprint.boolValue() && isForwardPressed(client) && !client.player.isShiftKeyDown()) {
            client.player.setSprinting(true);
        }

        compensateMotion(client);
        log(client);
    }

    @Override
    public void onMovementInput(MovementInputUpdateEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.options == null || event.getEntity() != client.player) {
            return;
        }
        if (client.screen != null || !client.player.isUsingItem() || client.player.isPassenger()) {
            return;
        }
        Input input = event.getInput();
        if (inputFix.boolValue()) {
            restoreInput(client, input);
        }
        float scale = 5.0F * strength.floatValue();
        input.forwardImpulse *= scale;
        input.leftImpulse *= scale;
        if (keepSprint.boolValue() && isForwardPressed(client) && !client.player.isShiftKeyDown()) {
            client.player.setSprinting(true);
        }
        log(client);
    }

    private void restoreInput(Minecraft client) {
        restoreInput(client, client.player.input);
    }

    private void restoreInput(Minecraft client, Input input) {
        float forward = 0.0F;
        float strafe = 0.0F;
        if (client.options.keyUp.isDown()) {
            forward += 1.0F;
        }
        if (client.options.keyDown.isDown()) {
            forward -= 1.0F;
        }
        if (client.options.keyLeft.isDown()) {
            strafe += 1.0F;
        }
        if (client.options.keyRight.isDown()) {
            strafe -= 1.0F;
        }
        input.forwardImpulse = forward;
        input.leftImpulse = strafe;
        input.up = client.options.keyUp.isDown();
        input.down = client.options.keyDown.isDown();
        input.left = client.options.keyLeft.isDown();
        input.right = client.options.keyRight.isDown();
        input.jumping = client.options.keyJump.isDown();
        input.shiftKeyDown = client.options.keyShift.isDown();
    }

    private void compensateMotion(Minecraft client) {
        Vec3 direction = requestedHorizontalDirection(client);
        if (direction.lengthSqr() < 0.0001D) {
            return;
        }

        Vec3 motion = client.player.getDeltaMovement();
        double targetSpeed = (client.player.isSprinting() ? 0.145D : 0.105D) * strength.doubleValue();
        if (!client.player.onGround()) {
            targetSpeed *= 0.72D;
        }
        double currentAlongDirection = motion.x * direction.x + motion.z * direction.z;
        if (currentAlongDirection >= targetSpeed) {
            return;
        }

        double correction = Math.min(targetSpeed - currentAlongDirection, targetSpeed * 0.65D);
        client.player.setDeltaMovement(motion.add(direction.x * correction, 0.0D, direction.z * correction));
    }

    private Vec3 requestedHorizontalDirection(Minecraft client) {
        double forward = 0.0D;
        double strafe = 0.0D;
        if (client.options.keyUp.isDown()) {
            forward += 1.0D;
        }
        if (client.options.keyDown.isDown()) {
            forward -= 1.0D;
        }
        if (client.options.keyLeft.isDown()) {
            strafe += 1.0D;
        }
        if (client.options.keyRight.isDown()) {
            strafe -= 1.0D;
        }
        if (forward == 0.0D && strafe == 0.0D) {
            return Vec3.ZERO;
        }

        double yaw = Math.toRadians(client.player.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        Vec3 direction = new Vec3(strafe * cos - forward * sin, 0.0D, forward * cos + strafe * sin);
        if (direction.lengthSqr() < 0.0001D) {
            return Vec3.ZERO;
        }
        return direction.normalize();
    }

    private boolean isForwardPressed(Minecraft client) {
        return client.options.keyUp.isDown() && !client.options.keyDown.isDown();
    }

    private void log(Minecraft client) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 5_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info("NoSlow active: item=" + client.player.getUseItem().getHoverName().getString()
            + ", sprint=" + client.player.isSprinting()
            + ", strength=" + strength.displayValue()
            + ", inputFix=" + inputFix.boolValue());
    }

    @Override
    public String description() {
        return "Compensates movement slowdown while using food, potions, bows, shields, and similar items.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "NoSlow strength=" + strength.displayValue()
            + " sprint=" + keepSprint.displayValue()
            + " input=" + inputFix.displayValue()
            + " event=Forge";
    }
}
