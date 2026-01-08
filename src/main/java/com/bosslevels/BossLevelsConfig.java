package com.bosslevels;

import net.runelite.client.config.*;

import java.awt.*;

@ConfigGroup("bosslevels")
public interface BossLevelsConfig extends Config
{
    @ConfigSection(
            name = "XP Drops",
            description = "XP drop visuals",
            position = 0
    )
    String xpSection = "xpSection";

    @ConfigSection(
            name = "Notifications",
            description = "Chat + fireworks",
            position = 1
    )
    String notifSection = "notifSection";

    // ---------- XP Drops toggles ----------
    @ConfigItem(
            keyName = "enableXpDrops",
            name = "Enable XP drops",
            description = "Show boss XP drops on screen",
            position = 0,
            section = xpSection
    )
    default boolean enableXpDrops() { return true; }

    @ConfigItem(
            keyName = "showBossNameInDrop",
            name = "Show boss name in drop",
            description = "Include the boss name in the XP drop text",
            position = 1,
            section = xpSection
    )
    default boolean showBossNameInDrop() { return false; }

    @ConfigItem(
            keyName = "showBossMarker",
            name = "Show boss marker",
            description = "Draw a small colored marker next to the XP drop (acts like an icon)",
            position = 2,
            section = xpSection
    )
    default boolean showBossMarker() { return true; }

    public enum ColorMode { GLOBAL, PER_BOSS }

    @ConfigItem(
            keyName = "colorMode",
            name = "Color mode",
            description = "Use one global color, or a unique deterministic color per boss",
            position = 3,
            section = xpSection
    )
    default ColorMode colorMode() { return ColorMode.GLOBAL; }

    @Alpha
    @ConfigItem(
            keyName = "globalXpColor",
            name = "Global XP color",
            description = "Color used when Color mode = GLOBAL",
            position = 4,
            section = xpSection
    )
    default Color globalXpColor() { return new Color(255, 200, 0, 255); }

    @Range(min = 1, max = 12)
    @ConfigItem(
            keyName = "maxVisibleDrops",
            name = "Max visible drops",
            description = "How many XP drops can be visible at once",
            position = 5,
            section = xpSection
    )
    default int maxVisibleDrops() { return 6; }

    // ---------- Motion / timing ----------
    @Range(min = 300, max = 5000)
    @ConfigItem(
            keyName = "durationMs",
            name = "Drop duration (ms)",
            description = "How long each XP drop lasts (controls speed)",
            position = 6,
            section = xpSection
    )
    default int durationMs() { return 1600; }

    @ConfigItem(
            keyName = "startX",
            name = "Start X (px)",
            description = "Start position X in pixels from the top-left of the game canvas",
            position = 7,
            section = xpSection
    )
    default int startX() { return 470; }

    @ConfigItem(
            keyName = "startY",
            name = "Start Y (px)",
            description = "Start position Y in pixels from the top-left of the game canvas",
            position = 8,
            section = xpSection
    )
    default int startY() { return 35; }

    @ConfigItem(
            keyName = "endX",
            name = "End X (px)",
            description = "End position X in pixels from the top-left of the game canvas",
            position = 9,
            section = xpSection
    )
    default int endX() { return 470; }

    @ConfigItem(
            keyName = "endY",
            name = "End Y (px)",
            description = "End position Y in pixels from the top-left of the game canvas",
            position = 10,
            section = xpSection
    )
    default int endY() { return 5; }

    @Range(min = 0, max = 30)
    @ConfigItem(
            keyName = "stackSpacing",
            name = "Stack spacing (px)",
            description = "Vertical spacing between stacked XP drops",
            position = 11,
            section = xpSection
    )
    default int stackSpacing() { return 14; }

    // ---------- Combine drops ----------
    @ConfigItem(
            keyName = "combineDrops",
            name = "Combine drops",
            description = "Combine multiple drops into one (like OSRS XP drops)",
            position = 12,
            section = xpSection
    )
    default boolean combineDrops() { return true; }

    @Range(min = 50, max = 1500)
    @ConfigItem(
            keyName = "combineWindowMs",
            name = "Combine window (ms)",
            description = "If a new drop happens within this window, it merges into the latest drop",
            position = 13,
            section = xpSection
    )
    default int combineWindowMs() { return 450; }

    // ---------- Notifications ----------
    @ConfigItem(
            keyName = "enableChatLine",
            name = "Chat message",
            description = "Print the Boss Levels line in chat",
            position = 0,
            section = notifSection
    )
    default boolean enableChatLine() { return true; }

    @ConfigItem(
            keyName = "enableFireworks",
            name = "Level-up fireworks",
            description = "Play the in-game level-up fireworks on boss level-up",
            position = 1,
            section = notifSection
    )
    default boolean enableFireworks() { return true; }
}
