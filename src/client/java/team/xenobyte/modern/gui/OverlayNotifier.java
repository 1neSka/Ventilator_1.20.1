package team.xenobyte.modern.gui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class OverlayNotifier {
    private static final int MAX_MESSAGES = 3;
    private static final long MESSAGE_NANOS = 3_000_000_000L;
    private static final Deque<Message> MESSAGES = new ArrayDeque<>();

    private OverlayNotifier() {
    }

    public static synchronized void push(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        long now = System.nanoTime();
        prune(now);
        while (MESSAGES.size() >= MAX_MESSAGES) {
            MESSAGES.removeFirst();
        }
        MESSAGES.addLast(new Message(text, now + MESSAGE_NANOS));
    }

    public static synchronized void clear() {
        MESSAGES.clear();
    }

    public static void render(GuiGraphics context) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.font == null || client.getWindow() == null) {
            return;
        }

        List<Message> snapshot;
        synchronized (OverlayNotifier.class) {
            long now = System.nanoTime();
            prune(now);
            snapshot = new ArrayList<>(MESSAGES);
        }
        if (snapshot.isEmpty()) {
            return;
        }

        int width = client.getWindow().getGuiScaledWidth();
        int y = 12;
        context.pose().pushPose();
        context.pose().translate(0.0F, 0.0F, 700.0F);
        for (Message message : snapshot) {
            int textWidth = client.font.width(message.text());
            int boxWidth = textWidth + 18;
            int x = (width - boxWidth) / 2;
            context.fill(x, y, x + boxWidth, y + 16, 0xaa10151c);
            context.fill(x, y, x + 2, y + 16, 0xff55d6ff);
            context.drawString(client.font, message.text(), x + 9, y + 5, 0xffffffff);
            y += 18;
        }
        context.pose().popPose();
    }

    private static void prune(long now) {
        while (!MESSAGES.isEmpty() && MESSAGES.peekFirst().expiresAtNanos() <= now) {
            MESSAGES.removeFirst();
        }
    }

    private record Message(String text, long expiresAtNanos) {
    }
}
