package com.tyrsguard;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IndexedSprite;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@PluginDescriptor(
    name = "Tyrs Guard Clan",
    description = "Submit screenshots, earn points, and bridge clan chat to Discord",
    tags = {"clan", "submissions", "points", "tyrsguard", "chat"}
)
public class TyrsGuardPlugin extends Plugin
{
    @Inject private ClientToolbar    clientToolbar;
    @Inject private TyrsGuardConfig  config;
    @Inject private Client           client;
    @Inject private DrawManager      drawManager;
    @Inject private ClientThread     clientThread;
    @Inject private EventBus         eventBus;

    private TyrsGuardPanel   panel;
    private NavigationButton navButton;

    private HttpClient             wsHttpClient;
    private WebSocket              webSocket;
    private ScheduledExecutorService wsScheduler;
    private final AtomicBoolean    wsConnected    = new AtomicBoolean(false);
    private final AtomicBoolean    wsConnecting   = new AtomicBoolean(false);
    private static final int       WS_RECONNECT_INTERVAL_SEC = 30;

    private ScheduledExecutorService outboundScheduler;
    private ScheduledExecutorService pingScheduler;
    private static final int PING_INTERVAL_SEC = 10;
    private final ConcurrentLinkedQueue<String[]> outboundQueue = new ConcurrentLinkedQueue<>();
    private static final int OUTBOUND_FLUSH_INTERVAL_SEC = 2;

    private int discordIconSlot = -1;

    private static final String DISCORD_PREFIX   = "[D] ";
    private static final long   DEDUP_WINDOW_MS  = 3000;
    private final Map<String, Long> recentMessages = Collections.synchronizedMap(
        new LinkedHashMap<String, Long>(50, 0.75f, true)
        {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest)
            {
                return size() > 50;
            }
        }
    );

    // ── Cached player name ────────────────────────────────────────────────────
    private          String  cachedLocalName = null; // safe to use after game state clears player

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void startUp() throws Exception
    {
        panel = new TyrsGuardPanel(config, this);

        final BufferedImage icon = ImageUtil.loadImageResource(getClass(),
            "/com/tyrsguard/icon.png");

        navButton = NavigationButton.builder()
            .tooltip("Tyrs Guard Clan")
            .icon(icon)
            .priority(5)
            .panel(panel)
            .build();

        clientToolbar.addNavigation(navButton);

        // Load Discord icon sprite on client thread
        clientThread.invoke(() -> {
            if (client.getModIcons() == null) return false;
            loadDiscordIcon();
            return true;
        });

        outboundScheduler = Executors.newSingleThreadScheduledExecutor();
        outboundScheduler.scheduleAtFixedRate(
            this::flushOutboundQueue,
            OUTBOUND_FLUSH_INTERVAL_SEC,
            OUTBOUND_FLUSH_INTERVAL_SEC,
            TimeUnit.SECONDS
        );

        if (config.chatBridgeEnabled())
        {
            startWebSocket();
        }

        wsScheduler = Executors.newSingleThreadScheduledExecutor();
        wsScheduler.scheduleAtFixedRate(
            this::checkWebSocketHealth,
            WS_RECONNECT_INTERVAL_SEC,
            WS_RECONNECT_INTERVAL_SEC,
            TimeUnit.SECONDS
        );

        pingScheduler = Executors.newSingleThreadScheduledExecutor();
        pingScheduler.scheduleAtFixedRate(
            this::sendWebSocketPing,
            PING_INTERVAL_SEC,
            PING_INTERVAL_SEC,
            TimeUnit.SECONDS
        );

        eventBus.register(this);


        log.debug("Tyrs Guard Clan plugin started");
    }

    @Override
    protected void shutDown() throws Exception
    {
        eventBus.unregister(this);
        clientToolbar.removeNavigation(navButton);

        stopWebSocket();

        if (outboundScheduler != null) { outboundScheduler.shutdownNow(); outboundScheduler = null; }
        if (wsScheduler      != null) { wsScheduler.shutdownNow();       wsScheduler       = null; }
        if (pingScheduler    != null) { pingScheduler.shutdownNow();     pingScheduler     = null; }

        outboundQueue.clear();
        log.debug("Tyrs Guard Clan plugin stopped");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Game events
    // ─────────────────────────────────────────────────────────────────────────

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState state = event.getGameState();

        if (state == GameState.LOGGED_IN)
        {
            // Cache the player name while we know it's available
            clientThread.invokeLater(() -> {
                if (client.getLocalPlayer() != null)
                    cachedLocalName = client.getLocalPlayer().getName();
                return true;
            });

            if (config.chatBridgeEnabled())
                startWebSocket();

            // Refresh clan coffer + armory data on every login / world hop arrival
            if (panel != null) panel.refreshClanHall();
            return;
        }

        if (state == GameState.HOPPING)
        {
            return;
        }

        if (state == GameState.LOGIN_SCREEN)
        {
            stopWebSocket();
        }
    }

    @Subscribe
    public void onClanChannelChanged(ClanChannelChanged event)
    {
        if (!config.chatBridgeEnabled()) return;
        stopWebSocket();
        if (event.getClanChannel() != null)
        {
            startWebSocket();
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (!config.chatBridgeEnabled()) return;

        ChatMessageType type = event.getType();

        if (type != ChatMessageType.CLAN_CHAT
            && type != ChatMessageType.CLAN_GUEST_CHAT
            && type != ChatMessageType.CLAN_MESSAGE
            && type != ChatMessageType.CLAN_GIM_CHAT
            && type != ChatMessageType.CLAN_GIM_MESSAGE)
        {
            return;
        }

        String sender  = stripTags(event.getName());
        String message = stripTags(event.getMessage());

        if (message.isEmpty()) return;
        // Allow empty sender for system notifications (level ups, quest completions, etc.)
        // Only block if it's NOT a notification type AND sender is empty
        if (event.getName().isEmpty() && event.getSender().isEmpty()
            && type != ChatMessageType.CLAN_MESSAGE
            && type != ChatMessageType.CLAN_GIM_MESSAGE) return;

        if (message.contains("To talk in your clan") || message.contains("start each line of chat with"))
            return;

        if (sender.startsWith(DISCORD_PREFIX) || message.startsWith(DISCORD_PREFIX)) return;

        String dedupKey = sender.toLowerCase() + "|" + message;
        long   now      = System.currentTimeMillis();
        Long   lastSeen = recentMessages.get(dedupKey);
        if (lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS) return;
        recentMessages.put(dedupKey, now);

        String rank = resolveClanRank(sender);

        String chatType;
        if (type == ChatMessageType.CLAN_CHAT || type == ChatMessageType.CLAN_GIM_CHAT)
            chatType = "clan";
        else if (type == ChatMessageType.CLAN_GUEST_CHAT)
            chatType = "guest";
        else
            chatType = "notification";

        outboundQueue.add(new String[]{ chatType, sender, message, rank });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebSocket — Discord → game
    // ─────────────────────────────────────────────────────────────────────────

    private void startWebSocket()
    {
        String apiUrl = config.botApiUrl().trim();
        if (apiUrl.isEmpty() || config.pluginApiSecret().isEmpty()) return;
        if (wsConnected.get() || wsConnecting.get()) return;

        String wsUrl = apiUrl
            .replaceFirst("^https://", "wss://")
            .replaceFirst("^http://",  "ws://")
            + "/ws/chat";

        wsConnecting.set(true);

        try
        {
            if (wsHttpClient == null)
                wsHttpClient = HttpClient.newHttpClient();

            wsHttpClient.newWebSocketBuilder()
                .header("X-Plugin-Secret", config.pluginApiSecret())
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener()
                {
                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last)
                    {
                        ws.request(1);
                        handleIncomingMessage(data.toString());
                        return null;
                    }

                    @Override
                    public void onOpen(WebSocket ws)
                    {
                        wsConnected.set(true);
                        wsConnecting.set(false);
                        webSocket = ws;
                        ws.request(1);
                        log.debug("Tyrs Guard Clan: WebSocket connected");
                    }

                    @Override
                    public CompletionStage<?> onPong(WebSocket ws, java.nio.ByteBuffer message)
                    {
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason)
                    {
                        wsConnected.set(false);
                        wsConnecting.set(false);
                        webSocket = null;
                        log.debug("Tyrs Guard Clan: WebSocket closed ({}) {}", statusCode, reason);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error)
                    {
                        wsConnected.set(false);
                        wsConnecting.set(false);
                        webSocket = null;
                        log.warn("Tyrs Guard Clan: WebSocket error: {}", error.getMessage());
                    }
                });
        }
        catch (Exception e)
        {
            wsConnecting.set(false);
            log.warn("Tyrs Guard Clan: WebSocket start failed: {}", e.getMessage());
        }
    }

    private void sendWebSocketPing()
    {
        if (!wsConnected.get() || webSocket == null) return;
        try
        {
            // Use native WebSocket ping frame — keeps Railway TCP connection alive
            webSocket.sendPing(java.nio.ByteBuffer.allocate(0));
        }
        catch (Exception e)
        {
            log.warn("Tyrs Guard Clan: WebSocket ping failed: {}", e.getMessage());
            wsConnected.set(false);
            webSocket = null;
        }
    }

    private void stopWebSocket()
    {
        if (webSocket != null)
        {
            try { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin shutting down"); }
            catch (Exception ignored) {}
            webSocket = null;
        }
        wsConnected.set(false);
        wsConnecting.set(false);
    }

    private void checkWebSocketHealth()
    {
        if (!config.chatBridgeEnabled()) return;
        if (client.getGameState() != GameState.LOGGED_IN) return;
        if (wsConnected.get() || wsConnecting.get()) return;
        log.debug("Tyrs Guard Clan: WebSocket not connected, attempting reconnect...");
        startWebSocket();
    }

    /**
     * Handles a JSON message pushed from the bot over the WebSocket.
     *
     * Shape: {"sender":"Name","message":"Hello"}
     */
    private void handleIncomingMessage(String json)
    {
        try
        {
            // ── Chat message path ──────────────────────────────────────────────
            String sender  = extractJsonStr(json, "sender");
            String message = extractJsonStr(json, "message");
            if (sender == null || message == null || message.isEmpty()) return;

            String cleanSender = normalizeToAscii(sender).trim();
            if (cleanSender.isEmpty()) cleanSender = "Discord";

            final String displayName = discordIconSlot >= 0
                ? String.format("<img=%d>%s", discordIconSlot, cleanSender)
                : DISCORD_PREFIX + cleanSender;

            final String finalMessage = message;

            // IMPORTANT: must run on client thread; clan channel name must be
            // retrieved inside invokeLater to avoid thread-safety issues
            clientThread.invokeLater(() ->
            {
                if (client.getGameState() != GameState.LOGGED_IN) return;

                ClanChannel clan = client.getClanChannel();
                // Use the actual clan name if available, empty string otherwise
                // Empty string is safe — OSRS will still show the message in clan chat
                String clanName = (clan != null && clan.getName() != null)
                    ? clan.getName()
                    : "";

                client.addChatMessage(
                    ChatMessageType.CLAN_CHAT,
                    displayName,
                    finalMessage,
                    clanName,
                    false
                );
            });
        }
        catch (Exception e)
        {
            log.warn("Tyrs Guard Clan: Failed to parse incoming WS message: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Outbound queue — game → Discord (HTTP POST, batched every 2s)
    // ─────────────────────────────────────────────────────────────────────────

    private void flushOutboundQueue()
    {
        if (outboundQueue.isEmpty()) return;

        String apiUrl = config.botApiUrl().trim();
        if (apiUrl.isEmpty()) return;

        StringBuilder jsonArray = new StringBuilder("[");
        int count = 0;
        String[] entry;
        while ((entry = outboundQueue.poll()) != null && count < 30)
        {
            if (count > 0) jsonArray.append(",");
            jsonArray.append("{")
                .append("\"type\":\"").append(escapeJson(entry[0])).append("\",")
                .append("\"sender\":\"").append(escapeJson(entry[1])).append("\",")
                .append("\"message\":\"").append(escapeJson(entry[2])).append("\",")
                .append("\"rank\":\"").append(escapeJson(entry[3])).append("\"")
                .append("}");
            count++;
        }
        jsonArray.append("]");

        final String payload = jsonArray.toString();

        new Thread(() -> {
            try
            {
                URL url = new URL(apiUrl + "/chat/batch");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("Content-Type",    "application/json");
                conn.setRequestProperty("X-Plugin-Secret", config.pluginApiSecret());

                try (OutputStream out = conn.getOutputStream())
                {
                    out.write(payload.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code != 200)
                    log.warn("Tyrs Guard Clan: Outbound batch got HTTP {}", code);
            }
            catch (Exception e)
            {
                log.warn("Tyrs Guard Clan: Outbound batch error: {}", e.getMessage());
            }
        }, "TyrsGuardClan-ChatSend").start();
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Discord icon sprite
    // FIX: path changed from /net/runelite/... to /com/tyrsguard/discord_icon.png
    // ─────────────────────────────────────────────────────────────────────────

    private void loadDiscordIcon()
    {
        final IndexedSprite[] modIcons = client.getModIcons();
        if (discordIconSlot != -1 || modIcons == null) return;

        try
        {
            // Corrected path — file must live at src/main/resources/com/tyrsguard/discord_icon.png
            BufferedImage image = ImageUtil.loadImageResource(getClass(),
                "/com/tyrsguard/discord_icon.png");
            if (image == null)
            {
                log.warn("Tyrs Guard Clan: discord_icon.png not found at /com/tyrsguard/discord_icon.png");
                return;
            }

            IndexedSprite sprite = ImageUtil.getImageIndexedSprite(image, client);
            discordIconSlot = modIcons.length;

            IndexedSprite[] extended = Arrays.copyOf(modIcons, modIcons.length + 1);
            extended[extended.length - 1] = sprite;
            client.setModIcons(extended);
            log.debug("Tyrs Guard Clan: Discord icon loaded at slot {}", discordIconSlot);
        }
        catch (Exception e)
        {
            log.warn("Tyrs Guard Clan: Could not load Discord icon, using text prefix instead: {}", e.getMessage());
            discordIconSlot = -1;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Clan rank resolution
    // ─────────────────────────────────────────────────────────────────────────

    private String resolveClanRank(String senderName)
    {
        ClanChannel clan = client.getClanChannel();
        if (clan == null) return "Guest";

        String clean = Text.removeTags(Text.toJagexName(senderName));
        ClanChannelMember member = clan.findMember(clean);
        if (member == null) return "Guest";

        var settings = client.getClanSettings();
        if (settings == null) return "Unknown";

        var title = settings.titleForRank(member.getRank());
        return title != null ? title.getName() : "Unranked";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String stripTags(String s)
    {
        if (s == null) return "";
        return s.replaceAll("<[^>]*>", "").trim();
    }

    private String escapeJson(String s)
    {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"",  "\\\"")
                .replace("\n",  "\\n")
                .replace("\r",  "\\r");
    }

    private String extractJsonStr(String json, String key)
    {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end)
                   .replace("\\n", "\n")
                   .replace("\\\"", "\"")
                   .replace("\\\\", "\\");
    }

    public String getLocalPlayerName()
    {
        if (client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null)
            return client.getLocalPlayer().getName();
        return null;
    }

    public void requestScreenshot(Consumer<BufferedImage> callback)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            SwingUtilities.invokeLater(() -> callback.accept(null));
            return;
        }
        drawManager.requestNextFrameListener(image -> {
            if (image instanceof BufferedImage)
            {
                BufferedImage copy = new BufferedImage(
                    image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
                copy.getGraphics().drawImage(image, 0, 0, null);
                copy.getGraphics().dispose();
                drawTimestamp(copy);
                SwingUtilities.invokeLater(() -> callback.accept(copy));
            }
            else { SwingUtilities.invokeLater(() -> callback.accept(null)); }
        });
    }

    private void drawTimestamp(BufferedImage image)
    {
        ZoneId localZone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(localZone);

        String zoneId = localZone.getId();
        String zoneLabel;
        if (zoneId.equals("America/New_York")       || zoneId.equals("America/Detroit")
            || zoneId.equals("America/Toronto")     || zoneId.equals("America/Montreal"))
            zoneLabel = now.getOffset().getTotalSeconds() == -18000 ? "EST" : "EDT";
        else if (zoneId.equals("America/Chicago")   || zoneId.equals("America/Winnipeg")
            || zoneId.equals("America/Indiana/Knox"))
            zoneLabel = now.getOffset().getTotalSeconds() == -21600 ? "CST" : "CDT";
        else if (zoneId.equals("America/Denver")    || zoneId.equals("America/Boise")
            || zoneId.equals("America/Edmonton")    || zoneId.equals("America/Regina"))
            zoneLabel = now.getOffset().getTotalSeconds() == -25200 ? "MST" : "MDT";
        else if (zoneId.equals("America/Los_Angeles") || zoneId.equals("America/Vancouver"))
            zoneLabel = now.getOffset().getTotalSeconds() == -28800 ? "PST" : "PDT";
        else if (zoneId.equals("America/Anchorage") || zoneId.equals("America/Juneau"))
            zoneLabel = now.getOffset().getTotalSeconds() == -32400 ? "AKST" : "AKDT";
        else if (zoneId.equals("Pacific/Honolulu")  || zoneId.equals("America/Adak"))
            zoneLabel = "HST";
        else if (zoneId.equals("America/Halifax")   || zoneId.equals("America/Glace_Bay"))
            zoneLabel = now.getOffset().getTotalSeconds() == -14400 ? "AST" : "ADT";
        else if (zoneId.equals("America/St_Johns"))
            zoneLabel = now.getOffset().getTotalSeconds() == -12600 ? "NST" : "NDT";
        else
            zoneLabel = now.format(DateTimeFormatter.ofPattern("zzz"));

        String timestamp = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy  h:mm:ss a"))
            + "  " + zoneLabel;

        ZonedDateTime cstTime = now.withZoneSameInstant(ZoneId.of("America/Chicago"));
        String cstLabel = cstTime.getOffset().getTotalSeconds() == -21600 ? "CST" : "CDT";
        String cstString = cstTime.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy  h:mm:ss a")) + "  " + cstLabel;

        ZonedDateTime ukTime = now.withZoneSameInstant(ZoneId.of("Europe/London"));
        String ukLabel = ukTime.getOffset().getTotalSeconds() == 0 ? "GMT" : "BST";
        String ukString = "(" + ukTime.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy  h:mm:ss a")) + "  " + ukLabel + ")";

        String line1 = timestamp + "   (" + cstString + ")";
        String line2 = ukString;

        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int fontSize = Math.max(16, image.getWidth() / 55);
        Font font = new Font("SansSerif", Font.BOLD, fontSize);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int lineHeight = fm.getAscent() + fm.getDescent() + 4;

        String[] lines = { line1, line2 };
        int yOffset = Math.max(60, image.getHeight() / 11);

        int line1Width = fm.stringWidth(line1);
        int line1x = (image.getWidth() - line1Width) / 2;
        int line1RightEdge = line1x + line1Width;

        for (int i = 0; i < lines.length; i++)
        {
            String line = lines[i];
            int textWidth = fm.stringWidth(line);
            int x = (i == 0) ? line1x : (line1RightEdge - textWidth);
            int y = yOffset + (i * lineHeight);

            g.setColor(new Color(0, 0, 0, 180));
            for (int dx = -2; dx <= 2; dx++)
                for (int dy = -2; dy <= 2; dy++)
                    g.drawString(line, x + dx, y + dy);

            g.setColor(Color.WHITE);
            g.drawString(line, x, y);
        }
        g.dispose();
    }

    @Provides
    TyrsGuardConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(TyrsGuardConfig.class);
    }

    private String normalizeToAscii(String input)
    {
        if (input == null) return "";

        StringBuilder step1 = new StringBuilder();
        for (int i = 0; i < input.length(); )
        {
            int cp = input.codePointAt(i);
            i += Character.charCount(cp);
            char plain = fancyToAscii(cp);
            if (plain != 0) step1.append(plain);
            else step1.appendCodePoint(cp);
        }

        String decomposed = Normalizer.normalize(step1.toString(), Normalizer.Form.NFD);
        StringBuilder result = new StringBuilder();
        for (char c : decomposed.toCharArray())
        {
            if (c >= 0x20 && c <= 0x7E) result.append(c);
        }
        return result.toString();
    }

    private char fancyToAscii(int cp)
    {
        if (cp >= 0x1D400 && cp <= 0x1D7FF)
        {
            int off = cp - 0x1D400;
            if (off < 52 * 13)
            {
                int pos = off % 52;
                if (pos < 26) return (char)('A' + pos);
                return (char)('a' + pos - 26);
            }
            if (cp >= 0x1D7CE && cp <= 0x1D7FF) return (char)('0' + (cp - 0x1D7CE) % 10);
        }
        if (cp >= 0x24B6 && cp <= 0x24CF) return (char)('A' + cp - 0x24B6);
        if (cp >= 0x24D0 && cp <= 0x24E9) return (char)('a' + cp - 0x24D0);
        if (cp >= 0xFF21 && cp <= 0xFF3A) return (char)('A' + cp - 0xFF21);
        if (cp >= 0xFF41 && cp <= 0xFF5A) return (char)('a' + cp - 0xFF41);
        if (cp >= 0xFF10 && cp <= 0xFF19) return (char)('0' + cp - 0xFF10);
        if (cp >= 0x2070 && cp <= 0x2079) return (char)('0' + cp - 0x2070);
        if (cp >= 0x2080 && cp <= 0x2089) return (char)('0' + cp - 0x2080);
        if (cp >= 0x1F1E6 && cp <= 0x1F1FF) return (char)('A' + cp - 0x1F1E6);
        return 0;
    }
}
