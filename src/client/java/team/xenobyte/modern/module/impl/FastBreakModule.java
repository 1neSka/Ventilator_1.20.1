package team.xenobyte.modern.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;
import team.xenobyte.modern.module.setting.ModuleSetting;
import team.xenobyte.modern.util.ReflectionField;

public class FastBreakModule extends XenoModule {
    private static final ReflectionField DESTROY_DELAY = new ReflectionField(MultiPlayerGameMode.class, "destroyDelay", "f_105195_", "h");
    private static final ReflectionField DESTROY_PROGRESS = new ReflectionField(MultiPlayerGameMode.class, "destroyProgress", "f_105193_", "f");

    private final ModuleSetting multiplier = setting("Multiplier", ModuleSetting.number("Multiplier", 2.0D, 1.0D, 10.0D, 0.5D)
        .describe("Extra client-side destroy progress applied while the attack key is held on a block."));
    private final ModuleSetting zeroDelay = setting("ZeroDelay", ModuleSetting.bool("ZeroDelay", true)
        .describe("Keeps the local block destroy delay at zero while mining."));
    private final ModuleSetting groundMine = setting("GroundMine", ModuleSetting.bool("GroundMine", true)
        .describe("Temporarily spoofs onGround while mining to test airborne mining penalty bypass."));
    private final ModuleSetting mode = setting("Mode", ModuleSetting.choice("Mode", 2, "Progress", "Pulse", "Hybrid")
        .describe("Progress is the old local progress boost, Pulse sends mini-nuker START/STOP, Hybrid does both."));
    private final ModuleSetting pulseRepeat = setting("PulseRepeat", ModuleSetting.number("PulseRepeat", 1.0D, 1.0D, 4.0D, 1.0D)
        .describe("How many START/STOP pairs are sent per FastBreak pulse."));
    private final ModuleSetting pulseDelay = setting("PulseDelay", ModuleSetting.number("PulseDelay", 0.0D, 0.0D, 4.0D, 1.0D)
        .describe("Ticks between FastBreak pulse packets."));

    private long lastLogNanos;
    private int groundSpoofPackets;
    private int finishSpoofPackets;
    private int manualStopPackets;
    private int pulsePackets;
    private int pulsePairs;
    private int pulseCooldownTicks;

    public FastBreakModule() {
        super("FastBreak", Category.WORLD, ModuleMode.TOGGLE);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.gameMode == null || client.options == null) {
            return;
        }
        if (!client.options.keyAttack.isDown() || !(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos pos = hit.getBlockPos();
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(client.level, pos) < 0.0F) {
            return;
        }

        if (pulseCooldownTicks > 0) {
            pulseCooldownTicks--;
        }

        boolean previousOnGround = client.player.onGround();
        float baseRaw = state.getDestroyProgress(client.player, client.level, pos);
        boolean spoofed = applyGroundMine(client);
        float baseProgress = state.getDestroyProgress(client.player, client.level, pos);
        if (zeroDelay.boolValue()) {
            DESTROY_DELAY.setInt(client.gameMode, 0);
        }

        String activeMode = mode.choiceValue();
        boolean progressMode = !"Pulse".equals(activeMode);
        boolean pulseMode = !"Progress".equals(activeMode);
        float current = DESTROY_PROGRESS.getFloat(client.gameMode, 0.0F);
        float next = current;
        if (progressMode) {
            next = Math.min(1.0F, current + baseProgress * Math.max(1.0F, multiplier.floatValue()));
            DESTROY_PROGRESS.setFloat(client.gameMode, next);
        }
        int sentPulsePairs = pulseMode ? sendPulse(client, hit, pos, baseProgress) : 0;
        boolean sentStop = sentPulsePairs <= 0 && progressMode && sendFinishPulse(client, hit, pos, next);
        client.player.setOnGround(previousOnGround);
        log(pos, baseRaw, baseProgress, current, next, previousOnGround, spoofed, sentStop, sentPulsePairs);
    }

    private boolean applyGroundMine(Minecraft client) {
        if (!groundMine.boolValue() || client.getConnection() == null) {
            return false;
        }
        client.player.setOnGround(true);
        client.getConnection().send(new ServerboundMovePlayerPacket.Pos(client.player.getX(), client.player.getY(), client.player.getZ(), true));
        client.getConnection().send(new ServerboundMovePlayerPacket.StatusOnly(true));
        groundSpoofPackets += 2;
        return true;
    }

    private int sendPulse(Minecraft client, BlockHitResult hit, BlockPos pos, float baseProgress) {
        if (client.getConnection() == null || pulseCooldownTicks > 0 || baseProgress <= 0.0F) {
            return 0;
        }
        int repeats = Math.max(1, pulseRepeat.intValue());
        for (int i = 0; i < repeats; i++) {
            client.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, hit.getDirection()));
            client.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, hit.getDirection()));
        }
        pulsePairs += repeats;
        pulsePackets += repeats * 2;
        pulseCooldownTicks = pulseDelay.intValue();
        return repeats;
    }

    private boolean sendFinishPulse(Minecraft client, BlockHitResult hit, BlockPos pos, float progress) {
        if (client.getConnection() == null || progress < 1.0F) {
            return false;
        }
        client.getConnection().send(new ServerboundMovePlayerPacket.Pos(client.player.getX(), client.player.getY(), client.player.getZ(), true));
        client.getConnection().send(new ServerboundMovePlayerPacket.StatusOnly(true));
        client.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, hit.getDirection()));
        finishSpoofPackets += 2;
        manualStopPackets++;
        return true;
    }

    private void log(BlockPos pos, float baseRaw, float baseProgress, float before, float after, boolean previousOnGround, boolean spoofed, boolean sentStop, int sentPulsePairs) {
        long now = System.nanoTime();
        if (now - lastLogNanos < 1_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        BootstrapLog.info("FastBreak tick: pos=" + pos.toShortString()
            + ", baseRaw=" + String.format(java.util.Locale.ROOT, "%.4f", baseRaw)
            + ", baseSpoof=" + String.format(java.util.Locale.ROOT, "%.4f", baseProgress)
            + ", before=" + String.format(java.util.Locale.ROOT, "%.4f", before)
            + ", after=" + String.format(java.util.Locale.ROOT, "%.4f", after)
            + ", multiplier=" + multiplier.displayValue()
            + ", mode=" + mode.choiceValue()
            + ", zeroDelay=" + zeroDelay.boolValue()
            + ", groundMine=" + groundMine.boolValue()
            + ", spoofed=" + spoofed
            + ", prevGround=" + previousOnGround
            + ", sentStop=" + sentStop
            + ", sentPulsePairs=" + sentPulsePairs
            + ", pulsePairs=" + pulsePairs
            + ", pulsePackets=" + pulsePackets
            + ", spoofPackets=" + groundSpoofPackets
            + ", finishSpoofPackets=" + finishSpoofPackets
            + ", manualStopPackets=" + manualStopPackets);
    }

    @Override
    public String description() {
        return "Accelerates local block breaking by zeroing destroy delay and boosting destroy progress.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "FastBreak multiplier=" + multiplier.displayValue()
            + " mode=" + mode.choiceValue()
            + " zeroDelay=" + zeroDelay.displayValue()
            + " groundMine=" + groundMine.displayValue()
            + " spoofPackets=" + groundSpoofPackets
            + " stopPackets=" + manualStopPackets
            + " pulsePairs=" + pulsePairs;
    }
}
