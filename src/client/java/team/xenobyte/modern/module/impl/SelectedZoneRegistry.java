package team.xenobyte.modern.module.impl;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

public final class SelectedZoneRegistry {
    private static final List<Zone> ZONES = new ArrayList<>();
    private static PendingPoint firstPoint;
    private static long version;

    private SelectedZoneRegistry() {
    }

    public static synchronized void setFirst(ResourceKey<Level> dimension, BlockPos pos) {
        firstPoint = new PendingPoint(dimension, pos.immutable());
        version++;
    }

    public static synchronized PendingPoint firstPoint() {
        return firstPoint;
    }

    public static synchronized AddResult addSecond(ResourceKey<Level> dimension, BlockPos second) {
        if (firstPoint == null) {
            return new AddResult(AddStatus.NO_FIRST, null);
        }
        if (!firstPoint.dimension().equals(dimension)) {
            return new AddResult(AddStatus.WRONG_DIMENSION, null);
        }
        Zone zone = Zone.between(dimension, firstPoint.pos(), second);
        ZONES.add(zone);
        firstPoint = null;
        version++;
        return new AddResult(AddStatus.ADDED, zone);
    }

    public static synchronized ClearResult clear() {
        int zones = ZONES.size();
        long blocks = totalBlocks();
        boolean hadFirst = firstPoint != null;
        ZONES.clear();
        firstPoint = null;
        version++;
        return new ClearResult(zones, blocks, hadFirst);
    }

    public static synchronized boolean contains(ResourceKey<Level> dimension, BlockPos pos) {
        for (Zone zone : ZONES) {
            if (zone.dimension().equals(dimension) && zone.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    public static synchronized List<Zone> snapshot(ResourceKey<Level> dimension) {
        return ZONES.stream().filter(zone -> zone.dimension().equals(dimension)).toList();
    }

    public static synchronized int size() {
        return ZONES.size();
    }

    public static synchronized long totalBlocks() {
        long total = 0L;
        for (Zone zone : ZONES) {
            total = Math.min(Long.MAX_VALUE, total + zone.volume());
        }
        return total;
    }

    public static synchronized long version() {
        return version;
    }

    public enum AddStatus {
        ADDED,
        NO_FIRST,
        WRONG_DIMENSION
    }

    public record AddResult(AddStatus status, Zone zone) {
    }

    public record ClearResult(int zones, long blocks, boolean hadFirst) {
    }

    public record PendingPoint(ResourceKey<Level> dimension, BlockPos pos) {
    }

    public record Zone(ResourceKey<Level> dimension, BlockPos min, BlockPos max, long volume) {
        private static Zone between(ResourceKey<Level> dimension, BlockPos first, BlockPos second) {
            BlockPos min = new BlockPos(
                Math.min(first.getX(), second.getX()),
                Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ())
            );
            BlockPos max = new BlockPos(
                Math.max(first.getX(), second.getX()),
                Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ())
            );
            long sizeX = (long)max.getX() - min.getX() + 1L;
            long sizeY = (long)max.getY() - min.getY() + 1L;
            long sizeZ = (long)max.getZ() - min.getZ() + 1L;
            long volume;
            try {
                volume = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
            } catch (ArithmeticException ignored) {
                volume = Long.MAX_VALUE;
            }
            return new Zone(dimension, min, max, volume);
        }

        public boolean contains(BlockPos pos) {
            return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        }

        public AABB box() {
            return new AABB(
                min.getX(), min.getY(), min.getZ(),
                max.getX() + 1.0D, max.getY() + 1.0D, max.getZ() + 1.0D
            );
        }
    }
}
