package team.xenobyte.modern.util;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import team.xenobyte.modern.bootstrap.BootstrapLog;

public final class MovementUtil {
    private MovementUtil() {
    }

    public static boolean hasFloorWithin(Minecraft client, double distance) {
        if (client == null || client.level == null || client.player == null) {
            return false;
        }

        AABB box = client.player.getBoundingBox();
        double[][] points = {
            {client.player.getX(), client.player.getZ()},
            {box.minX + 0.05D, box.minZ + 0.05D},
            {box.minX + 0.05D, box.maxZ - 0.05D},
            {box.maxX - 0.05D, box.minZ + 0.05D},
            {box.maxX - 0.05D, box.maxZ - 0.05D}
        };
        double step = 0.25D;
        for (double down = 0.05D; down <= distance; down += step) {
            double y = box.minY - down;
            for (double[] point : points) {
                BlockPos pos = BlockPos.containing(point[0], y, point[1]);
                BlockState state = client.level.getBlockState(pos);
                if (!state.getCollisionShape(client.level, pos).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void applyRescueJump(Minecraft client, String source) {
        if (client == null || client.player == null) {
            return;
        }
        Vec3 motion = client.player.getDeltaMovement();
        double y = Math.max(motion.y, 0.42D);
        client.player.setOnGround(true);
        client.player.setDeltaMovement(motion.x, y, motion.z);
        client.player.hasImpulse = true;
        client.player.fallDistance = 0.0F;
        BootstrapLog.info(source + " rescue jump: motionY="
            + String.format(Locale.ROOT, "%.3f", y)
            + ", onGround=" + client.player.onGround()
            + ", hasImpulse=" + client.player.hasImpulse);
    }
}
