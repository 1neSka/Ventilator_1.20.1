package team.xenobyte.modern.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class RenderUtil {
    private RenderUtil() {
    }

    public static void drawBox(WorldRenderContext context, AABB box, int color) {
        PoseStack poseStack = context.poseStack();
        if (poseStack == null) {
            return;
        }
        Vec3 camera = context.camera().getPosition();
        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            drawBox(poseStack, box, color);
        } finally {
            poseStack.popPose();
        }
    }

    public static void drawBoxBuffered(WorldRenderContext context, AABB box, int color) {
        drawBoxesBuffered(context, java.util.List.of(box), color);
    }

    public static void drawBoxesBuffered(WorldRenderContext context, Iterable<AABB> boxes, int color) {
        PoseStack poseStack = context.poseStack();
        if (poseStack == null) {
            return;
        }

        Vec3 camera = context.camera().getPosition();
        float a = alpha(color);
        float r = red(color);
        float g = green(color);
        float b = blue(color);
        MultiBufferSource.BufferSource buffer = MultiBufferSource.immediate(new BufferBuilder(256));

        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            setupLines();
            VertexConsumer consumer = buffer.getBuffer(RenderType.lines());
            for (AABB box : boxes) {
                LevelRenderer.renderLineBox(poseStack, consumer, box, r, g, b, a);
            }
            buffer.endBatch(RenderType.lines());
            restoreLines();
        } finally {
            poseStack.popPose();
        }
    }

    public static void drawBoxesImmediate(WorldRenderContext context, Iterable<AABB> boxes, int color) {
        PoseStack poseStack = context.poseStack();
        if (poseStack == null) {
            return;
        }

        Vec3 camera = context.camera().getPosition();
        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            setupLines();
            Matrix4f matrix = poseStack.last().pose();
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            for (AABB box : boxes) {
                drawBoxEdges(buffer, matrix, box, color);
            }
            BufferUploader.drawWithShader(buffer.end());
            restoreLines();
        } finally {
            poseStack.popPose();
        }
    }

    public static void drawFilledBoxesImmediate(WorldRenderContext context, Iterable<AABB> boxes, int color) {
        PoseStack poseStack = context.poseStack();
        if (poseStack == null) {
            return;
        }

        Vec3 camera = context.camera().getPosition();
        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            setupFills();
            Matrix4f matrix = poseStack.last().pose();
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (AABB box : boxes) {
                drawBoxFaces(buffer, matrix, box, color);
            }
            BufferUploader.drawWithShader(buffer.end());
            restoreFills();
        } finally {
            poseStack.popPose();
        }
    }

    public static void drawTracer(WorldRenderContext context, Vec3 target, int color) {
        PoseStack poseStack = context.poseStack();
        Minecraft client = Minecraft.getInstance();
        if (poseStack == null || client.player == null) {
            return;
        }
        Vec3 camera = context.camera().getPosition();
        Vec3 start = tracerStart(context, client);

        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            setupLines();
            Matrix4f matrix = poseStack.last().pose();
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            vertex(buffer, matrix, start.x, start.y, start.z, color);
            vertex(buffer, matrix, target.x, target.y, target.z, color);
            BufferUploader.drawWithShader(buffer.end());
            restoreLines();
        } finally {
            poseStack.popPose();
        }
    }

    public static void drawTracerBuffered(WorldRenderContext context, Vec3 target, int color) {
        drawTracersBuffered(context, java.util.List.of(target), color);
    }

    public static void drawTracersImmediate(WorldRenderContext context, Iterable<Vec3> targets, int color) {
        PoseStack poseStack = context.poseStack();
        Minecraft client = Minecraft.getInstance();
        if (poseStack == null || client.player == null) {
            return;
        }

        Vec3 camera = context.camera().getPosition();
        Vec3 start = tracerStart(context, client);
        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            setupLines();
            Matrix4f matrix = poseStack.last().pose();
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            for (Vec3 target : targets) {
                vertex(buffer, matrix, start.x, start.y, start.z, color);
                vertex(buffer, matrix, target.x, target.y, target.z, color);
            }
            BufferUploader.drawWithShader(buffer.end());
            restoreLines();
        } finally {
            poseStack.popPose();
        }
    }

    public static void drawScreenLine(GuiGraphics context, double x1, double y1, double x2, double y2, int color) {
        PoseStack poseStack = context.pose();
        setupLines();
        Matrix4f matrix = poseStack.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        vertex(buffer, matrix, x1, y1, 0.0D, color);
        vertex(buffer, matrix, x2, y2, 0.0D, color);
        BufferUploader.drawWithShader(buffer.end());
        restoreLines();
    }

    public static void drawTracersBuffered(WorldRenderContext context, Iterable<Vec3> targets, int color) {
        PoseStack poseStack = context.poseStack();
        Minecraft client = Minecraft.getInstance();
        if (poseStack == null || client.player == null) {
            return;
        }

        Vec3 camera = context.camera().getPosition();
        Vec3 start = tracerStart(context, client);
        MultiBufferSource.BufferSource buffer = MultiBufferSource.immediate(new BufferBuilder(64));

        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            setupLines();
            Matrix4f pose = poseStack.last().pose();
            Matrix3f normal = poseStack.last().normal();
            VertexConsumer consumer = buffer.getBuffer(RenderType.lines());
            for (Vec3 target : targets) {
                Vec3 delta = target.subtract(start);
                if (delta.lengthSqr() < 0.0001D) {
                    continue;
                }
                Vec3 direction = delta.normalize();
                vertex(consumer, pose, normal, start.x, start.y, start.z, direction, color);
                vertex(consumer, pose, normal, target.x, target.y, target.z, direction, color);
            }
            buffer.endBatch(RenderType.lines());
            restoreLines();
        } finally {
            poseStack.popPose();
        }
    }

    private static void drawBox(PoseStack poseStack, AABB box, int color) {
        setupLines();
        Matrix4f matrix = poseStack.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        drawBoxEdges(buffer, matrix, box, color);
        BufferUploader.drawWithShader(buffer.end());
        restoreLines();
    }

    private static void drawBoxEdges(BufferBuilder buffer, Matrix4f matrix, AABB box, int color) {
        double x1 = box.minX;
        double y1 = box.minY;
        double z1 = box.minZ;
        double x2 = box.maxX;
        double y2 = box.maxY;
        double z2 = box.maxZ;

        edge(buffer, matrix, x1, y1, z1, x2, y1, z1, color);
        edge(buffer, matrix, x2, y1, z1, x2, y1, z2, color);
        edge(buffer, matrix, x2, y1, z2, x1, y1, z2, color);
        edge(buffer, matrix, x1, y1, z2, x1, y1, z1, color);

        edge(buffer, matrix, x1, y2, z1, x2, y2, z1, color);
        edge(buffer, matrix, x2, y2, z1, x2, y2, z2, color);
        edge(buffer, matrix, x2, y2, z2, x1, y2, z2, color);
        edge(buffer, matrix, x1, y2, z2, x1, y2, z1, color);

        edge(buffer, matrix, x1, y1, z1, x1, y2, z1, color);
        edge(buffer, matrix, x2, y1, z1, x2, y2, z1, color);
        edge(buffer, matrix, x2, y1, z2, x2, y2, z2, color);
        edge(buffer, matrix, x1, y1, z2, x1, y2, z2, color);
    }

    private static void drawBoxFaces(BufferBuilder buffer, Matrix4f matrix, AABB box, int color) {
        double x1 = box.minX;
        double y1 = box.minY;
        double z1 = box.minZ;
        double x2 = box.maxX;
        double y2 = box.maxY;
        double z2 = box.maxZ;

        face(buffer, matrix, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, color);
        face(buffer, matrix, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, color);
        face(buffer, matrix, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, color);
        face(buffer, matrix, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, color);
        face(buffer, matrix, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, color);
        face(buffer, matrix, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, color);
    }

    private static void face(
        BufferBuilder buffer,
        Matrix4f matrix,
        double x1,
        double y1,
        double z1,
        double x2,
        double y2,
        double z2,
        double x3,
        double y3,
        double z3,
        double x4,
        double y4,
        double z4,
        int color
    ) {
        vertex(buffer, matrix, x1, y1, z1, color);
        vertex(buffer, matrix, x2, y2, z2, color);
        vertex(buffer, matrix, x3, y3, z3, color);
        vertex(buffer, matrix, x4, y4, z4, color);
    }

    private static void edge(BufferBuilder buffer, Matrix4f matrix, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
        vertex(buffer, matrix, x1, y1, z1, color);
        vertex(buffer, matrix, x2, y2, z2, color);
    }

    private static void vertex(BufferBuilder buffer, Matrix4f matrix, double x, double y, double z, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        buffer.vertex(matrix, (float)x, (float)y, (float)z).color(r, g, b, a).endVertex();
    }

    private static void vertex(VertexConsumer consumer, Matrix4f pose, Matrix3f normal, double x, double y, double z, Vec3 direction, int color) {
        consumer.vertex(pose, (float)x, (float)y, (float)z)
            .color(color >>> 16 & 255, color >>> 8 & 255, color & 255, color >>> 24 & 255)
            .normal(normal, (float)direction.x, (float)direction.y, (float)direction.z)
            .endVertex();
    }

    private static Vec3 tracerStart(WorldRenderContext context, Minecraft client) {
        if (client.options != null && client.options.getCameraType().isFirstPerson()) {
            Vector3f look = context.camera().getLookVector();
            return context.camera().getPosition().add(look.x() * 0.35D, look.y() * 0.35D, look.z() * 0.35D);
        }
        return client.player.getEyePosition(context.partialTick());
    }

    private static float alpha(int color) {
        return (color >>> 24 & 255) / 255.0F;
    }

    private static float red(int color) {
        return (color >>> 16 & 255) / 255.0F;
    }

    private static float green(int color) {
        return (color >>> 8 & 255) / 255.0F;
    }

    private static float blue(int color) {
        return (color & 255) / 255.0F;
    }

    private static void setupLines() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.lineWidth(1.5F);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private static void setupFills() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private static void restoreLines() {
        RenderSystem.lineWidth(1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void restoreFills() {
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
