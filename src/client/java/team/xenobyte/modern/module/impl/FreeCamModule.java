package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;
import team.xenobyte.modern.render.WorldRenderContext;

public class FreeCamModule extends XenoModule {
    private final ModuleSetting speed = setting("Speed", ModuleSetting.number("Speed", 1.0D, 0.1D, 5.0D, 0.1D)
        .describe("Free camera horizontal speed."));
    private final ModuleSetting vertical = setting("Vertical", ModuleSetting.number("Vertical", 1.0D, 0.1D, 5.0D, 0.1D)
        .describe("Free camera vertical speed."));
    private final ModuleSetting freezeBody = setting("FreezeBody", ModuleSetting.bool("FreezeBody", true)
        .describe("Keeps the local player body at the position where FreeCam was enabled."));

    private ArmorStand camera;
    private boolean hadBodySnapshot;
    private double bodyX;
    private double bodyY;
    private double bodyZ;
    private float bodyYaw;
    private float bodyPitch;
    private float cameraYaw;
    private float cameraPitch;
    private long lastLogNanos;

    public FreeCamModule() {
        super("FreeCam", Category.RENDER, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        createCamera(client);
    }

    @Override
    public void onDisable(Minecraft client) {
        if (client != null && client.player != null && client.getCameraEntity() == camera) {
            client.setCameraEntity(client.player);
        }
        restoreBodyRotation(client);
        if (camera != null) {
            camera.discard();
            camera = null;
        }
        hadBodySnapshot = false;
        BootstrapLog.info("FreeCam disabled and camera restored");
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.options == null) {
            return;
        }
        if (camera == null || camera.level() != client.level) {
            createCamera(client);
            if (camera == null) {
                return;
            }
        }

        applyMouseRotation(client);
        moveCamera(client);
        if (freezeBody.boolValue()) {
            freezeBody(client);
        } else {
            restoreBodyRotation(client);
        }
        if (client.getCameraEntity() != camera) {
            client.setCameraEntity(camera);
        }
        log(client);
    }

    private void createCamera(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        if (camera != null) {
            camera.discard();
        }
        bodyX = client.player.getX();
        bodyY = client.player.getY();
        bodyZ = client.player.getZ();
        bodyYaw = client.player.getYRot();
        bodyPitch = client.player.getXRot();
        cameraYaw = bodyYaw;
        cameraPitch = bodyPitch;
        hadBodySnapshot = true;

        camera = new ArmorStand(client.level, client.player.getX(), client.player.getY(), client.player.getZ());
        camera.setInvisible(true);
        camera.setNoGravity(true);
        camera.noPhysics = true;
        placeCamera(camera.getX(), camera.getY(), camera.getZ());
        client.setCameraEntity(camera);
        BootstrapLog.info("FreeCam enabled: body="
            + String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f", bodyX, bodyY, bodyZ)
            + ", yaw=" + String.format(java.util.Locale.ROOT, "%.1f", bodyYaw)
            + ", pitch=" + String.format(java.util.Locale.ROOT, "%.1f", bodyPitch));
    }

    private void applyMouseRotation(Minecraft client) {
        if (client == null || client.player == null || camera == null || !hadBodySnapshot) {
            return;
        }

        float yawDelta = Mth.wrapDegrees(client.player.getYRot() - bodyYaw);
        float pitchDelta = client.player.getXRot() - bodyPitch;
        if (Math.abs(yawDelta) > 0.0001F || Math.abs(pitchDelta) > 0.0001F) {
            cameraYaw = Mth.wrapDegrees(cameraYaw + yawDelta);
            cameraPitch = Mth.clamp(cameraPitch + pitchDelta, -90.0F, 90.0F);
            placeCamera(camera.getX(), camera.getY(), camera.getZ());
        }
        restoreBodyRotation(client);
    }

    private void moveCamera(Minecraft client) {
        Vec3 motion = requestedMotion(client);
        camera.noPhysics = true;
        camera.setNoGravity(true);
        camera.setDeltaMovement(motion);
        placeCamera(camera.getX() + motion.x, camera.getY() + motion.y, camera.getZ() + motion.z);
    }

    private Vec3 requestedMotion(Minecraft client) {
        double right = 0.0D;
        double forwardInput = 0.0D;
        double lift = 0.0D;
        double hSpeed = speed.doubleValue();
        double vSpeed = vertical.doubleValue();

        if (client.options.keyLeft.isDown()) {
            right -= 1.0D;
        }
        if (client.options.keyRight.isDown()) {
            right += 1.0D;
        }
        if (client.options.keyUp.isDown()) {
            forwardInput += 1.0D;
        }
        if (client.options.keyDown.isDown()) {
            forwardInput -= 1.0D;
        }
        if (client.options.keyJump.isDown()) {
            lift += vSpeed;
        }
        if (client.options.keyShift.isDown()) {
            lift -= vSpeed;
        }

        Vec3 look = Vec3.directionFromRotation(0.0F, cameraYaw);
        Vec3 forward = new Vec3(look.x, 0.0D, look.z);
        if (forward.lengthSqr() < 0.0001D) {
            forward = new Vec3(0.0D, 0.0D, 1.0D);
        } else {
            forward = forward.normalize();
        }
        Vec3 rightVector = new Vec3(-forward.z, 0.0D, forward.x);
        Vec3 horizontal = forward.scale(forwardInput).add(rightVector.scale(right));
        if (horizontal.lengthSqr() > 1.0D) {
            horizontal = horizontal.normalize();
        }
        return horizontal.scale(hSpeed).add(0.0D, lift, 0.0D);
    }

    private void placeCamera(double x, double y, double z) {
        camera.absMoveTo(x, y, z, cameraYaw, cameraPitch);
        camera.xo = camera.getX();
        camera.yo = camera.getY();
        camera.zo = camera.getZ();
        camera.xOld = camera.getX();
        camera.yOld = camera.getY();
        camera.zOld = camera.getZ();
        camera.yRotO = cameraYaw;
        camera.xRotO = cameraPitch;
        camera.setYHeadRot(cameraYaw);
        camera.yHeadRotO = cameraYaw;
        camera.yBodyRot = cameraYaw;
        camera.yBodyRotO = cameraYaw;
    }

    private void freezeBody(Minecraft client) {
        if (!hadBodySnapshot) {
            return;
        }
        client.player.setDeltaMovement(Vec3.ZERO);
        client.player.absMoveTo(bodyX, bodyY, bodyZ, bodyYaw, bodyPitch);
        client.player.xo = bodyX;
        client.player.yo = bodyY;
        client.player.zo = bodyZ;
        client.player.xOld = bodyX;
        client.player.yOld = bodyY;
        client.player.zOld = bodyZ;
        client.player.fallDistance = 0.0F;
        restoreBodyRotation(client);
    }

    private void restoreBodyRotation(Minecraft client) {
        if (client == null || client.player == null || !hadBodySnapshot) {
            return;
        }
        client.player.setYRot(bodyYaw);
        client.player.setXRot(bodyPitch);
        client.player.yRotO = bodyYaw;
        client.player.xRotO = bodyPitch;
        client.player.setYHeadRot(bodyYaw);
        client.player.yHeadRotO = bodyYaw;
        client.player.setYBodyRot(bodyYaw);
        client.player.yBodyRotO = bodyYaw;
    }

    @Override
    public void onHudRender(GuiGraphics context, float partialTick) {
        applyMouseRotation(Minecraft.getInstance());
    }

    @Override
    public void onWorldRender(WorldRenderContext context) {
        applyMouseRotation(Minecraft.getInstance());
    }

    private void log(Minecraft client) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 5_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info("FreeCam tick: camera="
            + String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f", camera.getX(), camera.getY(), camera.getZ())
            + ", yaw=" + String.format(java.util.Locale.ROOT, "%.1f", cameraYaw)
            + ", pitch=" + String.format(java.util.Locale.ROOT, "%.1f", cameraPitch)
            + ", speed=" + speed.displayValue()
            + ", vertical=" + vertical.displayValue()
            + ", freezeBody=" + freezeBody.boolValue()
            + ", activeCamera=" + (client.getCameraEntity() instanceof ArmorStand));
    }

    @Override
    public String description() {
        return "Detaches the local camera into a no-collision spectator-style view while optionally freezing the body.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        if (camera == null) {
            return "FreeCam camera=null";
        }
        return "FreeCam speed=" + speed.displayValue()
            + " yaw=" + String.format(java.util.Locale.ROOT, "%.0f", cameraYaw)
            + " pitch=" + String.format(java.util.Locale.ROOT, "%.0f", cameraPitch)
            + " pos=" + String.format(java.util.Locale.ROOT, "%.1f/%.1f/%.1f", camera.getX(), camera.getY(), camera.getZ());
    }
}
