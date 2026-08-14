package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.phys.Vec3;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;

public class FlyModule extends XenoModule {
    private final ModuleSetting mode = setting("Mode", ModuleSetting.choice("Mode", 0, "Motion", "Creative")
        .describe("Motion sets local velocity directly. Creative toggles local flying abilities."));
    private final ModuleSetting horizontal = setting("HSpeed", ModuleSetting.number("HSpeed", 0.8D, 0.1D, 5.0D, 0.1D)
        .describe("Horizontal flight speed for Motion mode."));
    private final ModuleSetting vertical = setting("VSpeed", ModuleSetting.number("VSpeed", 0.4D, 0.1D, 3.0D, 0.1D)
        .describe("Vertical flight speed for Motion mode."));
    private final ModuleSetting creativeSpeed = setting("CreativeSpeed", ModuleSetting.number("CreativeSpeed", 1.0D, 0.5D, 5.0D, 0.5D)
        .describe("Multiplier for vanilla-style Creative flight speed."));
    private final ModuleSetting inGui = setting("InGui", ModuleSetting.bool("InGui", false)
        .describe("Allows Motion mode flight while another GUI screen is open."));
    private final ModuleSetting noClip = setting("NoClip", ModuleSetting.bool("NoClip", false)
        .describe("Sets local noPhysics while Fly is enabled. Servers may still correct movement."));

    private boolean hadSnapshot;
    private boolean previousMayFly;
    private boolean previousFlying;
    private float previousFlyingSpeed;
    private boolean previousNoGravity;
    private boolean previousNoPhysics;
    private boolean creativeFlyingWanted;
    private boolean jumpWasDown;
    private int creativeGroundGraceTicks;
    private int damageStabilizeTicks;
    private float lastTrackedHealth = -1.0F;
    private double lastStableY = Double.NaN;
    private long lastJumpTapNanos;
    private long lastLogNanos;

    public FlyModule() {
        super("Fly", Category.MOVE, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        snapshot(client);
    }

    @Override
    public void onDisable(Minecraft client) {
        if (client == null || client.player == null || !hadSnapshot) {
            return;
        }
        Abilities abilities = client.player.getAbilities();
        abilities.mayfly = previousMayFly;
        abilities.flying = previousFlying;
        abilities.setFlyingSpeed(previousFlyingSpeed);
        client.player.setNoGravity(previousNoGravity);
        client.player.noPhysics = previousNoPhysics;
        client.player.onUpdateAbilities();
        hadSnapshot = false;
        creativeFlyingWanted = false;
        jumpWasDown = false;
        creativeGroundGraceTicks = 0;
        damageStabilizeTicks = 0;
        lastTrackedHealth = -1.0F;
        lastStableY = Double.NaN;
        lastJumpTapNanos = 0L;
        BootstrapLog.info("Fly restored previous abilities");
    }

    @Override
    public void onClientTickStart(Minecraft client) {
        if (client == null || client.player == null || client.options == null) {
            return;
        }
        trackDamageDrop(client);
        stabilizeDamageDrop(client);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.options == null) {
            return;
        }
        if (!hadSnapshot) {
            snapshot(client);
        }

        client.player.noPhysics = noClip.boolValue();
        client.player.fallDistance = 0.0F;
        trackDamageDrop(client);
        stabilizeDamageDrop(client);
        if ("Creative".equals(mode.choiceValue())) {
            applyCreative(client);
            return;
        }
        applyMotion(client);
    }

    private void applyCreative(Minecraft client) {
        pollCreativeDoubleJump(client);
        if (creativeGroundGraceTicks > 0) {
            creativeGroundGraceTicks--;
        }
        if (creativeFlyingWanted && creativeGroundGraceTicks == 0 && client.player.onGround() && client.player.getDeltaMovement().y <= 0.05D) {
            creativeFlyingWanted = false;
            BootstrapLog.info("Fly creative auto-land: flying=false");
        }
        Abilities abilities = client.player.getAbilities();
        abilities.mayfly = true;
        abilities.flying = creativeFlyingWanted;
        abilities.setFlyingSpeed(Math.max(0.025F, 0.05F * creativeSpeed.floatValue()));
        client.player.setNoGravity(creativeFlyingWanted || noClip.boolValue());
        stabilizeDamageDrop(client);
        if (noClip.boolValue() && creativeFlyingWanted) {
            Vec3 push = requestedMotion(client, 0.15D * creativeSpeed.doubleValue(), 0.15D * creativeSpeed.doubleValue());
            phasePush(client, push);
        }
        rememberStableY(client);
        client.player.onUpdateAbilities();
        log("Creative", client.player.getDeltaMovement());
    }

    private void applyMotion(Minecraft client) {
        if (client.screen != null && !inGui.boolValue()) {
            client.player.setDeltaMovement(0.0D, 0.0D, 0.0D);
            return;
        }

        Vec3 motion = requestedMotion(client, horizontal.doubleValue(), vertical.doubleValue());
        client.player.setNoGravity(true);
        client.player.setDeltaMovement(motion);
        client.player.setOnGround(true);
        stabilizeDamageDrop(client);
        rememberStableY(client);
        if (noClip.boolValue()) {
            phasePush(client, motion.scale(0.25D));
        }
        log("Motion", motion);
    }

    private void trackDamageDrop(Minecraft client) {
        float health = client.player.getHealth() + client.player.getAbsorptionAmount();
        if (lastTrackedHealth >= 0.0F && health < lastTrackedHealth - 0.05F) {
            damageStabilizeTicks = 14;
            if (Double.isNaN(lastStableY)) {
                lastStableY = client.player.getY();
            }
            BootstrapLog.info("Fly damage stabilizer armed: health=" + health
                + ", stableY=" + String.format(java.util.Locale.ROOT, "%.2f", lastStableY));
        }
        lastTrackedHealth = health;
        if (damageStabilizeTicks > 0) {
            damageStabilizeTicks--;
        }
    }

    private void stabilizeDamageDrop(Minecraft client) {
        if (damageStabilizeTicks <= 0 || client.player == null || client.options == null) {
            return;
        }
        client.player.fallDistance = 0.0F;
        client.player.setNoGravity(true);
        if ("Creative".equals(mode.choiceValue())) {
            Abilities abilities = client.player.getAbilities();
            abilities.mayfly = true;
            abilities.flying = creativeFlyingWanted;
        }

        boolean intentionalDown = client.options.keyShift.isDown();
        Vec3 motion = client.player.getDeltaMovement();
        if (!intentionalDown && motion.y < 0.0D) {
            client.player.setDeltaMovement(motion.x, 0.0D, motion.z);
        }
        if (!intentionalDown && !Double.isNaN(lastStableY) && client.player.getY() < lastStableY - 0.35D) {
            client.player.setPos(client.player.getX(), lastStableY, client.player.getZ());
        }
    }

    private void rememberStableY(Minecraft client) {
        if (client.player == null || client.options == null) {
            return;
        }
        if (damageStabilizeTicks <= 0 || client.options.keyShift.isDown() || client.options.keyJump.isDown()) {
            lastStableY = client.player.getY();
        }
    }

    private Vec3 requestedMotion(Minecraft client, double horizontalSpeed, double verticalSpeed) {
        double strafe = 0.0D;
        double forward = 0.0D;
        double lift = 0.0D;
        if (client.options.keyLeft.isDown()) {
            strafe += horizontalSpeed;
        }
        if (client.options.keyRight.isDown()) {
            strafe -= horizontalSpeed;
        }
        if (client.options.keyUp.isDown()) {
            forward += horizontalSpeed;
        }
        if (client.options.keyDown.isDown()) {
            forward -= horizontalSpeed;
        }
        if (client.options.keyJump.isDown()) {
            lift += verticalSpeed;
        }
        if (client.options.keyShift.isDown()) {
            lift -= verticalSpeed;
        }

        float yaw = client.player.getYRot() * (float)Math.PI / 180.0F;
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        return new Vec3(strafe * cos - forward * sin, lift, forward * cos + strafe * sin);
    }

    private void phasePush(Minecraft client, Vec3 push) {
        if (push.lengthSqr() < 0.0001D) {
            return;
        }
        client.player.noPhysics = true;
        client.player.setPos(client.player.getX() + push.x, client.player.getY() + push.y, client.player.getZ() + push.z);
    }

    private void pollCreativeDoubleJump(Minecraft client) {
        if (client.screen != null) {
            jumpWasDown = false;
            return;
        }

        boolean jumpDown = client.options.keyJump.isDown();
        if (jumpDown && !jumpWasDown) {
            long now = System.nanoTime();
            if (lastJumpTapNanos != 0L && now - lastJumpTapNanos <= 350_000_000L) {
                creativeFlyingWanted = !creativeFlyingWanted;
                creativeGroundGraceTicks = creativeFlyingWanted ? 8 : 0;
                lastJumpTapNanos = 0L;
                BootstrapLog.info("Fly creative double-space toggled: flying=" + creativeFlyingWanted
                    + ", speedMultiplier=" + creativeSpeed.displayValue());
            } else {
                lastJumpTapNanos = now;
            }
        }
        jumpWasDown = jumpDown;
    }

    private void snapshot(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }
        Abilities abilities = client.player.getAbilities();
        previousMayFly = abilities.mayfly;
        previousFlying = abilities.flying;
        previousFlyingSpeed = abilities.getFlyingSpeed();
        previousNoGravity = client.player.isNoGravity();
        previousNoPhysics = client.player.noPhysics;
        creativeFlyingWanted = abilities.flying;
        jumpWasDown = false;
        creativeGroundGraceTicks = 0;
        damageStabilizeTicks = 0;
        lastTrackedHealth = client.player.getHealth() + client.player.getAbsorptionAmount();
        lastStableY = client.player.getY();
        lastJumpTapNanos = 0L;
        hadSnapshot = true;
        BootstrapLog.info("Fly stored previous abilities");
    }

    private void log(String activeMode, Vec3 motion) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 5_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info("Fly tick: mode=" + activeMode
            + ", motion=" + String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f", motion.x, motion.y, motion.z)
            + ", noClip=" + noClip.boolValue());
    }

    @Override
    public String description() {
        return "Experimental local flight with Motion and Creative-style modes. Servers may correct movement.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        if (client == null || client.player == null) {
            return "Fly player=null";
        }
        return "Fly mode=" + mode.choiceValue() + " noPhysics=" + client.player.noPhysics;
    }
}
