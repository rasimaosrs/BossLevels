package com.bosslevels;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@PluginDescriptor(
		name = "Boss Levels",
		description = "Gives bosses fake XP and levels based on RuneLite KC",
		tags = {"boss", "xp", "levels"}
)
public class BossLevelsPlugin extends Plugin
{
	/* ===================== CONSTANTS ===================== */

	private static final String CONFIG_GROUP = "bosslevels";
	private static final Pattern KC_PATTERN =
			Pattern.compile("^Your (.+) kill count is: (\\d+)\\.$");
	private static final Pattern RL_KC_PREFIX_PATTERN =
			Pattern.compile("^(.+?):\\s*(.+?)\\s+kill count:\\s*(\\d+)\\s*$");
	private static final Pattern RL_KC_BODY_PATTERN =
			Pattern.compile("^(.+?)\\s+kill count:\\s*(\\d+)\\s*$");

	/* ===================== INJECTED ===================== */

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ConfigManager configManager;
	@Inject private ClientToolbar clientToolbar;
	@Inject private OverlayManager overlayManager;
	@Inject private BossLevelsConfig config;

	@Provides
	BossLevelsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BossLevelsConfig.class);
	}

	/* ===================== STATE ===================== */

	private final Map<BossDefinition, Long> bossXp = new EnumMap<>(BossDefinition.class);
	private final Map<BossDefinition, Integer> bossLevels = new EnumMap<>(BossDefinition.class);
	private final Map<BossDefinition, Integer> lastKcSeen = new EnumMap<>(BossDefinition.class);

	// Pre-scaled icons
	private final Map<BossDefinition, BufferedImage> bossIcons16 = new EnumMap<>(BossDefinition.class);

	private int spotAnimKey = 1;

	/* ===================== PANEL UI ===================== */

	private BossLevelsPanel panel;
	private NavigationButton navButton;

	private void openBossDetail(BossDefinition boss)
	{
		if (panel == null)
		{
			return;
		}

		long xp = bossXp.getOrDefault(boss, 0L);
		int level = bossLevels.getOrDefault(boss, 1);

		int cur = xpForLevel(level);
		int nxt = xpForNextLevel(level);
		int pct = (level >= 99) ? 100 : (int) Math.floor(100.0 * (xp - cur) / Math.max(1, (nxt - cur)));

		BufferedImage icon = bossIcons16.get(boss);
		panel.showBoss(boss, xp, level, pct, icon);
	}

	/* ===================== XP DROP OVERLAY ===================== */

	private BossLevelsOverlay xpDropOverlay;

	/* ===================== STARTUP/SHUTDOWN ===================== */

	private BufferedImage pluginIcon;

	@Override
	protected void startUp()
	{
		pluginIcon = ImageUtil.loadImageResource(BossLevelsPlugin.class, "icons/plugin_icon.png");

		// Load XP + level state
		for (BossDefinition boss : BossDefinition.values())
		{
			long xp = loadLong(xpKey(boss), 0L);
			bossXp.put(boss, xp);
			bossLevels.put(boss, levelForXp(xp));
			lastKcSeen.put(boss, -1);
		}

		// Load icons
		bossIcons16.clear();
		for (BossDefinition boss : BossDefinition.values())
		{
			BufferedImage img = ImageUtil.loadImageResource(BossLevelsPlugin.class, "icons/" + boss.iconFile);
			if (img != null)
			{
				bossIcons16.put(boss, ImageUtil.resizeImage(img, 16, 16));
			}
		}

		// Panel
		panel = new BossLevelsPanel();
		panel.setOpenBossDetailConsumer(this::openBossDetail);

		navButton = NavigationButton.builder()
				.tooltip("Boss Levels")
				.icon(pluginIcon)
				.priority(5)
				.panel(panel)
				.build();

		SwingUtilities.invokeLater(() ->
		{
			clientToolbar.addNavigation(navButton);
			panel.rebuildOverview(bossXp, bossLevels, bossIcons16);
		});

		// XP overlay
		xpDropOverlay = new BossLevelsOverlay(client, this::colorForBoss, config, bossIcons16);
		overlayManager.add(xpDropOverlay);
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			SwingUtilities.invokeLater(() -> clientToolbar.removeNavigation(navButton));
		}
		navButton = null;
		panel = null;

		if (xpDropOverlay != null)
		{
			overlayManager.remove(xpDropOverlay);
			xpDropOverlay = null;
		}

		bossIcons16.clear();
	}

	/* ===================== CHAT HANDLER ===================== */

	@Subscribe
	@SuppressWarnings("unused")
	public void onChatMessage(ChatMessage event)
	{
		// Avoid ChatMessageType mismatches
		final String msg = Text.removeTags(event.getMessage()).trim();
		Matcher m = KC_PATTERN.matcher(msg);
		if (m.matches())
		{
			BossDefinition boss = findBossByKcName(m.group(1));
			if (boss != null)
			{
				applyKcUpdate(boss, Integer.parseInt(m.group(2)));
			}
			return;
		}

		m = RL_KC_PREFIX_PATTERN.matcher(msg);
		if (m.matches())
		{
			if (!isLocalPlayerName(m.group(1)))
			{
				return;
			}

			BossDefinition boss = findBossByKcName(m.group(2));
			if (boss != null)
			{
				applyKcUpdate(boss, Integer.parseInt(m.group(3)));
			}
			return;
		}

		m = RL_KC_BODY_PATTERN.matcher(msg);
		if (!m.matches())
		{
			return;
		}

		final String senderFromEvent = Text.removeTags(event.getName()).trim();
		if (!isLocalPlayerName(senderFromEvent))
		{
			return;
		}

		BossDefinition boss = findBossByKcName(m.group(1));
		if (boss != null)
		{
			applyKcUpdate(boss, Integer.parseInt(m.group(2)));
		}
	}

	private void applyKcUpdate(BossDefinition boss, int kc)
	{
		int previousKc = lastKcSeen.getOrDefault(boss, -1);
		int gainedKills = (previousKc == -1) ? 1 : (kc - previousKc);
		lastKcSeen.put(boss, kc);

		if (gainedKills <= 0)
		{
			return;
		}

		int oldLevel = bossLevels.getOrDefault(boss, 1);

		long newXp = (long) kc * boss.xpPerKill;
		int newLevel = levelForXp(newXp);

		bossXp.put(boss, newXp);
		bossLevels.put(boss, newLevel);
		saveLong(xpKey(boss), newXp);

		long gainedXp = (long) gainedKills * boss.xpPerKill;

		// Screen XP drop
		if (config.enableXpDrops() && xpDropOverlay != null)
		{
			xpDropOverlay.pushDrop(boss, gainedXp);
		}

		// Update panel and auto-open the boss that changed
		if (panel != null)
		{
			SwingUtilities.invokeLater(() ->
			{
				panel.rebuildOverview(bossXp, bossLevels, bossIcons16);
				openBossDetail(boss);
			});
		}

		// Optional chat line
		if (config.enableChatLine())
		{
			client.addChatMessage(
					ChatMessageType.GAMEMESSAGE,
					"",
					"Boss Levels: " + boss.kcName + " +" + gainedXp +
							" xp (Total: " + newXp +
							", Level: " + newLevel + ")",
					null
			);
		}

		// Optional fireworks
		if (config.enableFireworks() && newLevel > oldLevel)
		{
			playLevelUpFireworks(newLevel);
		}
	}

	private boolean isLocalPlayerName(String sender)
	{
		Player p = client.getLocalPlayer();
		if (p == null || p.getName() == null)
		{
			return false;
		}
		return p.getName().trim().equalsIgnoreCase(sender.trim());
	}

	/* ===================== FIREWORKS ===================== */

	private void playLevelUpFireworks(int level)
	{
		final int anim = (level >= 99) ? SpotanimID.LEVELUP_99_ANIM : SpotanimID.LEVELUP_ANIM;

		clientThread.invoke(() ->
		{
			Player p = client.getLocalPlayer();
			if (p != null)
			{
				p.createSpotAnim(spotAnimKey++, anim, 0, 0);
			}
		});
	}

	/* ===================== XP CURVE ===================== */

	private static int levelForXp(long xp)
	{
		for (int level = 1; level < 99; level++)
		{
			if (xp < xpForLevel(level + 1))
			{
				return level;
			}
		}
		return 99;
	}

	private static int xpForLevel(int level)
	{
		double points = 0;
		for (int i = 1; i < level; i++)
		{
			points += Math.floor(i + 300.0 * Math.pow(2.0, i / 7.0));
		}
		return (int) Math.floor(points / 4.0);
	}

	private static int xpForNextLevel(int level)
	{
		if (level >= 99)
		{
			return xpForLevel(99);
		}
		return xpForLevel(level + 1);
	}

	/* ===================== CONFIG PERSISTENCE ===================== */

	private String xpKey(BossDefinition boss)
	{
		return "xp_" + boss.configKey;
	}

	private long loadLong(String key, long def)
	{
		String v = configManager.getConfiguration(CONFIG_GROUP, key);
		if (v == null)
		{
			return def;
		}

		try
		{
			return Long.parseLong(v);
		}
		catch (NumberFormatException e)
		{
			return def;
		}
	}

	private void saveLong(String key, long value)
	{
		configManager.setConfiguration(CONFIG_GROUP, key, Long.toString(value));
	}

	/* ===================== HELPERS ===================== */

	private BossDefinition findBossByKcName(String name)
	{
		String normalized = name == null ? "" : name.trim();
		for (BossDefinition boss : BossDefinition.values())
		{
			if (boss.kcName.equalsIgnoreCase(normalized))
			{
				return boss;
			}
		}
		return null;
	}

	private Color colorForBoss(BossDefinition boss)
	{
		if (config.colorMode() == BossLevelsConfig.ColorMode.GLOBAL)
		{
			return config.globalXpColor();
		}

		float hue = (boss.ordinal() * 0.21f) % 1.0f;
		Color base = Color.getHSBColor(hue, 0.70f, 1.0f);
		int a = config.globalXpColor().getAlpha();
		return new Color(base.getRed(), base.getGreen(), base.getBlue(), a);
	}
}
