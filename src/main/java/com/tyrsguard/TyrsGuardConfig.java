package com.tyrsguard;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("tyrsguard")
public interface TyrsGuardConfig extends Config
{
    // ── Sections ──────────────────────────────────────────────────────────────

    @ConfigSection(
        name = "Bot Connection",
        description = "Settings for connecting to the Tyrs Guard Clan Discord bot",
        position = 0
    )
    String botSection = "bot";

    @ConfigSection(
        name = "Chat Bridge",
        description = "Settings for the clan chat ↔ Discord bridge",
        position = 1
    )
    String chatSection = "chat";

    // ── Bot Connection ────────────────────────────────────────────────────────

    @ConfigItem(
        keyName = "botApiUrl",
        name = "Bot API URL",
        description = "The URL of your Discord bot's HTTP API (e.g. http://your-server-ip:4000)",
        section = "bot",
        position = 0
    )
    default String botApiUrl() { return ""; }

    @ConfigItem(
        keyName = "pluginApiSecret",
        name = "API Secret",
        description = "The secret token set in your bot's .env (PLUGIN_API_SECRET)",
        section = "bot",
        position = 1,
        secret = true
    )
    default String pluginApiSecret() { return ""; }

    @ConfigItem(
        keyName = "discordId",
        name = "Your Discord ID",
        description = "Your Discord user ID (right-click your name → Copy User ID)",
        section = "bot",
        position = 2
    )
    default String discordId() { return ""; }

    // ── Chat Bridge ───────────────────────────────────────────────────────────

    @ConfigItem(
        keyName = "chatBridgeEnabled",
        name = "Enable Chat Bridge",
        description = "Bridge clan chat messages to Discord and Discord messages into clan chat",
        section = "chat",
        position = 0
    )
    default boolean chatBridgeEnabled() { return true; }
}
