package team.xenobyte.modern.render;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;

public final class WorldRenderContext {
    private final PoseStack poseStack;
    private final Camera camera;
    private final float partialTick;

    public WorldRenderContext(PoseStack poseStack, Camera camera, float partialTick) {
        this.poseStack = poseStack;
        this.camera = camera;
        this.partialTick = partialTick;
    }

    public PoseStack poseStack() {
        return poseStack;
    }

    public Camera camera() {
        return camera;
    }

    public float partialTick() {
        return partialTick;
    }
}
