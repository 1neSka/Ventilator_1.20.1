package team.xenobyte.modern.module.impl;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import team.xenobyte.modern.bootstrap.BootstrapLog;
import team.xenobyte.modern.gui.ModuleMessageLog;
import team.xenobyte.modern.module.Category;
import team.xenobyte.modern.module.ModuleMode;
import team.xenobyte.modern.module.XenoModule;

public class LoggerModule extends XenoModule {
    private static final String HANDLER_NAME = "xenobyte_packet_logger";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_FIELD_DEPTH = 3;
    private static final int MAX_FIELD_COUNT = 24;
    private static final int MAX_TEXT = 400;
    private static final int MAX_BYTEBUF_BYTES = 512;
    private static final int MAX_LINES = 60_000;

    private BufferedWriter writer;
    private Path logPath;
    private Channel channel;
    private String lastScreen = "";
    private String lastMenu = "";
    private String lastUseItem = "";
    private final AtomicInteger inboundPackets = new AtomicInteger();
    private final AtomicInteger outboundPackets = new AtomicInteger();
    private final AtomicInteger skippedPackets = new AtomicInteger();
    private final AtomicInteger uiEvents = new AtomicInteger();
    private final AtomicInteger writeFailures = new AtomicInteger();
    private int lines;
    private boolean limitReached;

    public LoggerModule() {
        super("Logger", Category.MISC, ModuleMode.TOGGLE);
    }

    @Override
    public void onEnable(Minecraft client) {
        openLog(client);
        install(client);
        logUiState(client, true);
        ModuleMessageLog.push("Logger", logPath == null ? "failed to open log" : "log: " + logPath.getFileName());
    }

    @Override
    public void onDisable(Minecraft client) {
        writeLine("LOGGER disabled inbound=" + inboundPackets.get()
            + " outbound=" + outboundPackets.get()
            + " skipped=" + skippedPackets.get()
            + " ui=" + uiEvents.get()
            + " failures=" + writeFailures.get()
            + " limitReached=" + limitReached);
        removeHandler();
        closeLog();
        ModuleMessageLog.push("Logger", logPath == null ? "stopped" : "saved: " + logPath.getFileName());
    }

    @Override
    public void onTick(Minecraft client) {
        if (writer == null) {
            return;
        }
        if (channel == null || !channel.isActive() || channel.pipeline().get(HANDLER_NAME) == null) {
            install(client);
        }
        logUiState(client, false);
    }

    private void openLog(Minecraft client) {
        inboundPackets.set(0);
        outboundPackets.set(0);
        skippedPackets.set(0);
        uiEvents.set(0);
        writeFailures.set(0);
        lines = 0;
        limitReached = false;
        closeLog();

        Path directory = resolveLogDirectory();
        String fileName = "xenobyte-packet-log-" + LocalDateTime.now().format(FILE_TIME) + ".txt";
        logPath = directory.resolve(fileName);
        try {
            Files.createDirectories(directory);
            writer = Files.newBufferedWriter(
                logPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            writeLine("LOGGER enabled");
            writeLine("file=" + logPath.getFileName());
            if (client != null && client.level != null) {
                writeLine("level=" + client.level.dimension().location()
                    + " playerPresent=" + (client.player != null));
            }
            BootstrapLog.info("Logger opened: " + logPath.getFileName());
        } catch (IOException error) {
            writer = null;
            BootstrapLog.error("Logger open failed: " + logPath.getFileName(), error);
        }
    }

    private Path resolveLogDirectory() {
        String packageDir = System.getProperty("xenobyte-modern.packageDir", "");
        if (!packageDir.isBlank()) {
            return Path.of(packageDir);
        }

        try {
            CodeSource source = LoggerModule.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                URI uri = source.getLocation().toURI();
                Path path = Path.of(uri);
                if (Files.isRegularFile(path)) {
                    return path.getParent();
                }
                if (Files.isDirectory(path)) {
                    return path;
                }
            }
        } catch (Exception ignored) {
            // Fall through to temp.
        }
        return Path.of(System.getProperty("java.io.tmpdir"));
    }

    private void closeLog() {
        BufferedWriter current = writer;
        writer = null;
        if (current != null) {
            try {
                current.flush();
                current.close();
            } catch (IOException error) {
                BootstrapLog.error("Logger close failed", error);
            }
        }
    }

    private void install(Minecraft client) {
        if (client == null || client.getConnection() == null) {
            return;
        }
        try {
            Connection connection = client.getConnection().getConnection();
            Channel found = findChannel(connection);
            if (found == null) {
                writeLine("INSTALL failed: no Netty channel found on " + connection.getClass().getName());
                return;
            }
            ChannelPipeline pipeline = found.pipeline();
            if (pipeline.get(HANDLER_NAME) == null) {
                PacketLoggerHandler handler = new PacketLoggerHandler(this);
                if (pipeline.get("packet_handler") != null) {
                    pipeline.addBefore("packet_handler", HANDLER_NAME, handler);
                } else {
                    pipeline.addLast(HANDLER_NAME, handler);
                }
                writeLine("INSTALL ok pipeline=" + pipeline.names());
                BootstrapLog.info("Logger packet hook installed: " + logPath);
            }
            channel = found;
        } catch (Throwable error) {
            writeLine("INSTALL failed: " + error.getClass().getName() + ": " + error.getMessage());
            BootstrapLog.error("Logger packet hook install failed", error);
        }
    }

    private Channel findChannel(Connection connection) {
        if (connection == null) {
            return null;
        }
        Class<?> type = connection.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (!Channel.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(connection);
                    if (value instanceof Channel found) {
                        return found;
                    }
                } catch (Throwable ignored) {
                    // Try the next field.
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private void removeHandler() {
        Channel current = channel;
        channel = null;
        if (current == null) {
            return;
        }
        try {
            current.eventLoop().execute(() -> {
                try {
                    if (current.pipeline().get(HANDLER_NAME) != null) {
                        current.pipeline().remove(HANDLER_NAME);
                    }
                } catch (Throwable error) {
                    BootstrapLog.error("Logger packet hook remove failed", error);
                }
            });
        } catch (Throwable error) {
            BootstrapLog.error("Logger packet hook remove schedule failed", error);
        }
    }

    private void logUiState(Minecraft client, boolean force) {
        if (client == null) {
            return;
        }
        String screen = client.screen == null
            ? "none"
            : client.screen.getClass().getName() + " title=" + safeText(client.screen.getTitle().getString());
        if (force || !screen.equals(lastScreen)) {
            lastScreen = screen;
            uiEvents.incrementAndGet();
            writeLine("UI screen=" + screen);
        }

        String menu = "none";
        if (client.player != null && client.player.containerMenu != null) {
            menu = client.player.containerMenu.getClass().getName()
                + " id=" + client.player.containerMenu.containerId
                + " slots=" + client.player.containerMenu.slots.size();
        }
        if (force || !menu.equals(lastMenu)) {
            lastMenu = menu;
            uiEvents.incrementAndGet();
            writeLine("UI menu=" + menu);
        }

        String useItem = "none";
        if (client.player != null && client.player.isUsingItem()) {
            useItem = itemStackSummary(client.player.getUseItem())
                + " hand=" + client.player.getUsedItemHand()
                + " remaining=" + client.player.getUseItemRemainingTicks();
        }
        if (force || !useItem.equals(lastUseItem)) {
            lastUseItem = useItem;
            if (!"none".equals(useItem)) {
                uiEvents.incrementAndGet();
                writeLine("UI usingItem=" + useItem);
            }
        }
    }

    private void recordPacket(String direction, Object packet) {
        try {
            if (!(packet instanceof Packet<?>)) {
                return;
            }
            if (shouldSkip(packet)) {
                skippedPackets.incrementAndGet();
                return;
            }

            if ("IN".equals(direction)) {
                inboundPackets.incrementAndGet();
            } else {
                outboundPackets.incrementAndGet();
            }

            String packetName = packet.getClass().getName();
            writeLine("PACKET " + direction
                + " side=" + inferSide(direction, packetName)
                + " kind=" + classify(packetName)
                + " packet=" + packetName
                + " details={" + packetDetails(packet) + "}");
        } catch (Throwable error) {
            writeLine("PACKET " + direction + " logger-error=" + error.getClass().getName() + ": " + safeText(error.getMessage()));
        }
    }

    private boolean shouldSkip(Object packet) {
        String name = packet.getClass().getName();
        if (name.contains("ClientboundBlockEntityDataPacket")) {
            return true;
        }
        if (name.contains("CustomPayload") && hasCustomPayloadChannel(packet, "jitl:main")) {
            return true;
        }
        if (name.contains("KeepAlive")
            || name.contains("Ping")
            || name.contains("Pong")
            || name.contains("MovePlayerPacket")
            || name.contains("PlayerInputPacket")
            || name.contains("ClientboundMoveEntityPacket")
            || name.contains("ClientboundPlayerPositionPacket")
            || name.contains("ClientboundTeleportEntityPacket")
            || name.contains("ClientboundSetEntityMotionPacket")
            || name.contains("ClientboundRotateHeadPacket")
            || name.contains("ClientboundLevelChunk")
            || name.contains("ClientboundChunksBiomes")
            || name.contains("ClientboundLightUpdate")
            || name.contains("ClientboundForgetLevelChunk")
            || name.contains("ClientboundSectionBlocksUpdate")
            || name.contains("ClientboundBlockUpdate")
            || name.contains("ClientboundSetChunkCacheCenterPacket")
            || name.contains("ClientboundSetTimePacket")
            || name.contains("ClientboundSoundPacket")
            || name.contains("ClientboundSoundEntityPacket")
            || name.contains("ClientboundEntityEventPacket")
            || name.contains("ServerboundSwingPacket")
            || name.contains("ClientboundBundlePacket")) {
            return true;
        }
        if (name.contains("ServerboundPlayerActionPacket")) {
            String action = fieldValueByName(packet, "action");
            return action.contains("DESTROY_BLOCK");
        }
        return false;
    }

    private boolean hasCustomPayloadChannel(Object packet, String channelName) {
        Class<?> type = packet.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(packet);
                    if (value instanceof ResourceLocation id && channelName.equals(id.toString())) {
                        return true;
                    }
                    if (value != null && channelName.equals(String.valueOf(value))) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // Try the next field.
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private String fieldValueByName(Object target, String wanted) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getName().equalsIgnoreCase(wanted)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    return String.valueOf(field.get(target));
                } catch (Throwable ignored) {
                    return "";
                }
            }
            type = type.getSuperclass();
        }
        return "";
    }

    private String inferSide(String direction, String packetName) {
        if (packetName.contains(".Serverbound")) {
            return "client->server/request";
        }
        if (packetName.contains(".Clientbound")) {
            return "server->client/response";
        }
        return "IN".equals(direction) ? "server->client" : "client->server";
    }

    private String classify(String packetName) {
        if (packetName.contains("CustomPayload")) {
            return "custom-payload/mod-channel";
        }
        if (packetName.contains("Interact")) {
            return "entity-interact-or-attack";
        }
        if (packetName.contains("UseItemOn")) {
            return "use-item-on-block";
        }
        if (packetName.contains("UseItem")) {
            return "use-item";
        }
        if (packetName.contains("Container") || packetName.contains("SetCreativeModeSlot")) {
            return "container/inventory";
        }
        if (packetName.contains("OpenScreen") || packetName.contains("HorseScreenOpen")) {
            return "open-screen";
        }
        if (packetName.contains("Merchant") || packetName.contains("SelectTrade")) {
            return "trading";
        }
        if (packetName.contains("Chat") || packetName.contains("Command")) {
            return "chat-command";
        }
        if (packetName.contains("Recipe") || packetName.contains("PlaceRecipe")) {
            return "recipe";
        }
        if (packetName.contains("SetCarriedItem")) {
            return "hotbar-selection";
        }
        if (packetName.contains("PlayerAbilities")) {
            return "abilities";
        }
        return "general";
    }

    private String packetDetails(Object packet) {
        StringBuilder builder = new StringBuilder();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        Class<?> type = packet.getClass();
        int count = 0;
        while (type != null && count < MAX_FIELD_COUNT) {
            for (Field field : type.getDeclaredFields()) {
                if (count >= MAX_FIELD_COUNT) {
                    break;
                }
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(packet);
                    if (builder.length() > 0) {
                        builder.append(", ");
                    }
                    builder.append(field.getName()).append("=").append(valueSummary(value, 0, seen));
                    count++;
                } catch (Throwable error) {
                    if (builder.length() > 0) {
                        builder.append(", ");
                    }
                    builder.append(field.getName()).append("=<").append(error.getClass().getSimpleName()).append(">");
                    count++;
                }
            }
            type = type.getSuperclass();
        }
        if (count >= MAX_FIELD_COUNT) {
            builder.append(", ...");
        }
        return builder.toString();
    }

    private String valueSummary(Object value, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (value == null) {
            return "null";
        }
        if (seen.containsKey(value)) {
            return "<cycle>";
        }
        if (value instanceof String string) {
            return '"' + safeText(string) + '"';
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return safeText(String.valueOf(value));
        }
        if (value instanceof ResourceLocation id) {
            return id.toString();
        }
        if (value instanceof BlockPos pos) {
            return pos.toShortString();
        }
        if (value instanceof Component component) {
            return '"' + safeText(component.getString()) + '"';
        }
        if (value instanceof ItemStack stack) {
            return itemStackSummary(stack);
        }
        if (value instanceof ByteBuf buffer) {
            return byteBufSummary(buffer);
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(item -> "Optional[" + valueSummary(item, depth + 1, seen) + "]").orElse("Optional.empty");
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            return type.getComponentType().getSimpleName() + "[" + Array.getLength(value) + "]";
        }
        if (value instanceof Collection<?> collection) {
            if (depth >= MAX_FIELD_DEPTH) {
                return value.getClass().getSimpleName() + "(size=" + collection.size() + ")";
            }
            seen.put(value, Boolean.TRUE);
            StringBuilder builder = new StringBuilder(value.getClass().getSimpleName()).append("(size=").append(collection.size()).append(", first=[");
            int index = 0;
            for (Object item : collection) {
                if (index >= 4) {
                    builder.append("...");
                    break;
                }
                if (index > 0) {
                    builder.append(", ");
                }
                builder.append(valueSummary(item, depth + 1, seen));
                index++;
            }
            seen.remove(value);
            return builder.append("])").toString();
        }
        if (value instanceof Map<?, ?> map) {
            return value.getClass().getSimpleName() + "(size=" + map.size() + ")";
        }
        String className = value.getClass().getName();
        if (className.contains("ByteBuf") || className.contains("FriendlyByteBuf")) {
            return reflectiveByteBufSummary(value);
        }
        String text = String.valueOf(value);
        if (text.equals(className + "@" + Integer.toHexString(System.identityHashCode(value)))) {
            return value.getClass().getSimpleName();
        }
        return safeText(text);
    }

    private String itemStackSummary(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        StringBuilder builder = new StringBuilder()
            .append(id)
            .append("x")
            .append(stack.getCount());
        if (stack.getDamageValue() > 0 || stack.getMaxDamage() > 0) {
            builder.append(" dmg=").append(stack.getDamageValue()).append("/").append(stack.getMaxDamage());
        }
        if (stack.hasTag()) {
            builder.append(" nbt=").append(safeText(String.valueOf(stack.getTag())));
        }
        String name = stack.getHoverName().getString();
        if (name != null && !name.isBlank()) {
            builder.append(" name=\"").append(safeText(name)).append('"');
        }
        return builder.toString();
    }

    private String byteBufSummary(ByteBuf buffer) {
        try {
            int readerIndex = buffer.readerIndex();
            int readable = Math.max(0, buffer.readableBytes());
            int length = Math.min(readable, MAX_BYTEBUF_BYTES);
            byte[] bytes = new byte[length];
            if (length > 0) {
                buffer.getBytes(readerIndex, bytes);
            }
            return buffer.getClass().getSimpleName()
                + "[readable=" + readable
                + ", readerIndex=" + readerIndex
                + ", hex=" + bytesToHex(bytes)
                + (readable > length ? "...+" + (readable - length) + "b" : "")
                + ", ascii=\"" + asciiPreview(bytes) + "\"]";
        } catch (Throwable error) {
            return buffer.getClass().getSimpleName() + "[dumpError=" + error.getClass().getSimpleName() + "]";
        }
    }

    private String reflectiveByteBufSummary(Object value) {
        try {
            int readerIndex = ((Number)value.getClass().getMethod("readerIndex").invoke(value)).intValue();
            int readable = ((Number)value.getClass().getMethod("readableBytes").invoke(value)).intValue();
            int length = Math.min(Math.max(0, readable), MAX_BYTEBUF_BYTES);
            byte[] bytes = new byte[length];
            if (length > 0) {
                value.getClass().getMethod("getBytes", int.class, byte[].class).invoke(value, readerIndex, bytes);
            }
            return value.getClass().getSimpleName()
                + "[readable=" + readable
                + ", readerIndex=" + readerIndex
                + ", hex=" + bytesToHex(bytes)
                + (readable > length ? "...+" + (readable - length) + "b" : "")
                + ", ascii=\"" + asciiPreview(bytes) + "\"]";
        } catch (Throwable ignored) {
            return value.getClass().getSimpleName();
        }
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        char[] out = new char[bytes.length * 3 - 1];
        char[] hex = "0123456789abcdef".toCharArray();
        int index = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                out[index++] = ' ';
            }
            int value = bytes[i] & 0xFF;
            out[index++] = hex[value >>> 4];
            out[index++] = hex[value & 0x0F];
        }
        return new String(out);
    }

    private String asciiPreview(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length);
        for (byte raw : bytes) {
            int value = raw & 0xFF;
            if (value >= 32 && value <= 126) {
                builder.append((char)value);
            } else {
                builder.append('.');
            }
        }
        return safeText(builder.toString());
    }

    private String safeText(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        if (clean.length() > MAX_TEXT) {
            return clean.substring(0, MAX_TEXT) + "...";
        }
        return clean;
    }

    private synchronized void writeLine(String line) {
        if (writer == null || limitReached) {
            return;
        }
        try {
            if (lines >= MAX_LINES) {
                limitReached = true;
                writer.write("[" + LocalDateTime.now() + "] LOGGER line limit reached: " + MAX_LINES);
                writer.newLine();
                writer.flush();
                return;
            }
            writer.write("[" + LocalDateTime.now() + "] " + line);
            writer.newLine();
            lines++;
            if (lines % 128 == 0) {
                writer.flush();
            }
        } catch (IOException error) {
            writeFailures.incrementAndGet();
        }
    }

    @Override
    public String description() {
        return "Writes selected packet, GUI, container, and interaction events to a timestamped log file.";
    }

    @Override
    public String runtimeInfo(Minecraft client) {
        return "Logger in=" + inboundPackets.get()
            + " out=" + outboundPackets.get()
            + " skip=" + skippedPackets.get()
            + " ui=" + uiEvents.get()
            + " file=" + (logPath == null ? "none" : logPath.getFileName());
    }

    private static final class PacketLoggerHandler extends ChannelDuplexHandler {
        private final LoggerModule owner;

        private PacketLoggerHandler(LoggerModule owner) {
            this.owner = owner;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            owner.recordPacket("IN", message);
            super.channelRead(context, message);
        }

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            owner.recordPacket("OUT", message);
            super.write(context, message, promise);
        }
    }
}
