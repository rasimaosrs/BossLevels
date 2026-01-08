package com.bosslevels;

import com.google.inject.Provides;
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
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
		long xp = bossXp.getOrDefault(boss, 0L);
		int level = bossLevels.getOrDefault(boss, 1);

		int cur = xpForLevel(level);
		int nxt = xpForNextLevel(level);
		int pct = (level >= 99) ? 100 : (int) Math.floor(100.0 * (xp - cur) / Math.max(1, (nxt - cur)));

		BufferedImage icon = bossIcons16.get(boss);

		panel.showBoss(boss, xp, level, pct, icon);
	}



	/* ===================== XP DROP OVERLAY ===================== */

	private BossXpDropOverlay xpDropOverlay;

	/* ===================== STARTUP/SHUTDOWN ===================== */
	private BufferedImage pluginIcon;

	@Override
	protected void startUp()
	{
		pluginIcon = ImageUtil.loadImageResource(
				BossLevelsPlugin.class,
				"icons/plugin_icon.png"
		);

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
			BufferedImage img = ImageUtil.loadImageResource(
					BossLevelsPlugin.class,
					"icons/" + boss.iconFile
			);

			if (img != null)
			{
				bossIcons16.put(boss, ImageUtil.resizeImage(img, 16, 16));
			}
		}

		// Panel
		panel = new BossLevelsPanel();
		BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		navButton = NavigationButton.builder()
				.tooltip("Boss Levels")
				.icon(pluginIcon)
				.priority(5)
				.panel(panel)
				.build();

		SwingUtilities.invokeLater(() ->
		{
			clientToolbar.addNavigation(navButton);
			panel.rebuildOverview(bossXp, bossLevels, bossIcons16, this::openBossDetail);
		});


		// XP overlay
		xpDropOverlay = new BossXpDropOverlay(client, this::colorForBoss, config, bossIcons16);
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
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		String msg = Text.removeTags(event.getMessage()).trim();
		Matcher m = KC_PATTERN.matcher(msg);

		if (!m.matches())
		{
			return;
		}

		String bossName = m.group(1);
		int kc = Integer.parseInt(m.group(2));

		BossDefinition boss = findBossByKcName(bossName);
		if (boss == null)
		{
			return;
		}

		int previousKc = lastKcSeen.get(boss);
		int gainedKills = (previousKc == -1) ? 1 : kc - previousKc;
		lastKcSeen.put(boss, kc);

		if (gainedKills <= 0)
		{
			return;
		}

		int oldLevel = bossLevels.get(boss);

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
		if (panel != null)
		{
			SwingUtilities.invokeLater(() ->
			{
				panel.rebuildOverview(bossXp, bossLevels, bossIcons16, this::openBossDetail);
				openBossDetail(boss); // auto-open on XP gained
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

		// Update panel
		if (panel != null)
		{
			SwingUtilities.invokeLater(() ->
					panel.rebuildOverview(bossXp, bossLevels, bossIcons16, this::openBossDetail)
			);

		}
	}

	/* ===================== FIREWORKS ===================== */

	private void playLevelUpFireworks(int level)
	{
		final int anim = (level >= 99)
				? SpotanimID.LEVELUP_99_ANIM
				: SpotanimID.LEVELUP_ANIM;

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
		if (level <= 1) return 0;

		int points = 0;
		int output = 0;

		for (int lvl = 2; lvl <= level; lvl++)
		{
			points += Math.floor(lvl - 1 + 300.0 * Math.pow(2.0, (lvl - 1) / 7.0));
			output = (int) Math.floor(points / 4.0);
		}
		return output;
	}

	private static int xpForNextLevel(int level)
	{
		if (level >= 99) return xpForLevel(99);
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
		if (v == null) return def;

		try { return Long.parseLong(v); }
		catch (NumberFormatException e) { return def; }
	}

	private void saveLong(String key, long value)
	{
		configManager.setConfiguration(CONFIG_GROUP, key, Long.toString(value));
	}

	/* ===================== HELPERS ===================== */

	private BossDefinition findBossByKcName(String name)
	{
		for (BossDefinition boss : BossDefinition.values())
		{
			if (boss.kcName.equals(name))
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

	/* ===================== PANEL CLASS ===================== */

	private static class BossLevelsPanel extends PluginPanel
	{
		private final CardLayout cardLayout = new CardLayout();
		private final JPanel root = new JPanel(cardLayout);

		// Overview (grid)
		private final JPanel grid = new JPanel();
		private final JScrollPane gridScroll;

		// Detail
		private final JPanel detail = new JPanel(new BorderLayout());
		private final JButton backButton = new JButton("Back");
		private final JLabel detailTitle = new JLabel();
		private final JLabel detailXp = new JLabel();
		private final JLabel detailPct = new JLabel();
		private final JTextArea milestonesArea = new JTextArea();

		private final NumberFormat nf = NumberFormat.getInstance();

		private BossDefinition selectedBoss = null;

		BossLevelsPanel()
		{
			setLayout(new BorderLayout());
			add(root, BorderLayout.CENTER);

			// ---------- Overview card ----------
			grid.setBorder(new EmptyBorder(10, 10, 10, 10));
			grid.setLayout(new GridLayout(0, 3, 18, 18));

			JPanel gridWrapper = new JPanel(new BorderLayout());
			gridWrapper.setOpaque(false);
			gridWrapper.add(grid, BorderLayout.NORTH); //pins grid to top

			gridScroll = new JScrollPane(gridWrapper);
			gridScroll.setBorder(null);

			root.add(gridScroll, "overview");


			// ---------- Detail card ----------
			JPanel header = new JPanel();
			header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
			header.setOpaque(false);

// Row 1: title (full width)
			detailTitle.setFont(detailTitle.getFont().deriveFont(Font.BOLD, 16f));
			detailTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
			header.add(detailTitle);

// Row 2: xp + % (on the next line)
			JPanel statsRow = new JPanel(new BorderLayout());
			statsRow.setOpaque(false);
			statsRow.setAlignmentX(Component.LEFT_ALIGNMENT);

			detailXp.setHorizontalAlignment(SwingConstants.LEFT);
			detailPct.setHorizontalAlignment(SwingConstants.RIGHT);

			statsRow.add(detailXp, BorderLayout.WEST);
			statsRow.add(detailPct, BorderLayout.EAST);

			header.add(Box.createVerticalStrut(2));
			header.add(statsRow);


			JPanel topRow = new JPanel(new BorderLayout());
			topRow.setBorder(new EmptyBorder(0, 0, 8, 0));
			topRow.add(backButton, BorderLayout.WEST);

			JPanel topWrap = new JPanel();
			topWrap.setLayout(new BoxLayout(topWrap, BoxLayout.Y_AXIS));
			topWrap.setBorder(new EmptyBorder(8, 0, 8, 0));
			topWrap.add(topRow);
			topWrap.add(header);

			milestonesArea.setEditable(false);
			milestonesArea.setLineWrap(true);
			milestonesArea.setWrapStyleWord(true);
			milestonesArea.setBorder(new EmptyBorder(6, 0, 6, 0));
			milestonesArea.setOpaque(false);

			JScrollPane milestonesScroll = new JScrollPane(milestonesArea);
			milestonesScroll.setBorder(null);

			detail.add(topWrap, BorderLayout.NORTH);
			detail.add(milestonesScroll, BorderLayout.CENTER);

			root.add(detail, "detail");

			backButton.addActionListener(e -> showOverview());
			showOverview();
		}

		void showOverview()
		{
			selectedBoss = null;
			cardLayout.show(root, "overview");
			SwingUtilities.invokeLater(() -> gridScroll.getVerticalScrollBar().setValue(0));
		}

		void showBoss(BossDefinition boss,
					  long xp,
					  int level,
					  int pct,
					  BufferedImage icon)
		{
			selectedBoss = boss;

			// Title: icon + name + level
			if (icon != null)
			{
				Image scaled = icon.getScaledInstance(20, 20, Image.SCALE_SMOOTH);
				detailTitle.setIcon(new ImageIcon(scaled));
				detailTitle.setIconTextGap(6);
			}
			else
			{
				detailTitle.setIcon(null);
			}

			detailTitle.setText(
					"<html>" +
							boss.kcName + " — Lvl " + level +
							"</html>"
			);

			detailXp.setText(nf.format(xp) + " xp");
			detailPct.setText(pct + "%");
			detailTitle.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < boss.milestones.size(); i++)
			{
				sb.append(boss.milestones.get(i));

				if (i < boss.milestones.size() - 1)
				{
					sb.append("\n\n");
				}
			}
			milestonesArea.setText(sb.toString().trim());

			cardLayout.show(root, "detail");
		}

		/**
		 * Rebuilds the overview grid. Call this whenever levels/xp change.
		 */
		void rebuildOverview(Map<BossDefinition, Long> xpMap,
							 Map<BossDefinition, Integer> levelMap,
							 Map<BossDefinition, BufferedImage> iconMap,
							 java.util.function.Consumer<BossDefinition> onBossClicked)
		{
			grid.removeAll();

			for (BossDefinition boss : BossDefinition.values())
			{
				int level = levelMap.getOrDefault(boss, 1);
				long xp = xpMap.getOrDefault(boss, 0L);

				// show "--" if XP is 0
				String levelText = (xp <= 0) ? "--" : String.valueOf(level);

				BufferedImage icon = iconMap.get(boss);
				JLabel iconLabel = new JLabel();
				if (icon != null)
				{
					Image scaled = icon.getScaledInstance(19, 19, Image.SCALE_SMOOTH);
					iconLabel.setIcon(new ImageIcon(scaled));
				}

				JLabel levelLabel = new JLabel(levelText, SwingConstants.CENTER);
				levelLabel.setForeground(Color.LIGHT_GRAY);

				JPanel cell = new JPanel();
				cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
				cell.setOpaque(false);

				iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
				levelLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

				cell.add(iconLabel);
				cell.add(Box.createVerticalStrut(6));
				cell.add(levelLabel);

				cell.addMouseListener(new MouseAdapter()
				{
					@Override
					public void mouseClicked(MouseEvent e)
					{
						onBossClicked.accept(boss);
					}

					@Override
					public void mouseEntered(MouseEvent e)
					{
						cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
					}
				});

				grid.add(cell);
			}

			grid.revalidate();
			grid.repaint();
		}
	}


	/* ===================== XP DROP OVERLAY ===================== */

	private interface BossColorProvider
	{
		Color get(BossDefinition boss);
	}

	private static class BossXpDropOverlay extends Overlay
	{
		private static class Drop
		{
			BossDefinition boss;
			long xp;
			long startMs;

			Drop(BossDefinition boss, long xp, long startMs)
			{
				this.boss = boss;
				this.xp = xp;
				this.startMs = startMs;
			}
		}

		private final Client client;
		private final BossColorProvider colorProvider;
		private final BossLevelsConfig config;
		private final Map<BossDefinition, BufferedImage> iconMap16;

		private final Deque<Drop> drops = new ArrayDeque<>();

		BossXpDropOverlay(Client client, BossColorProvider colorProvider, BossLevelsConfig config,
						  Map<BossDefinition, BufferedImage> iconMap16)
		{
			this.client = client;
			this.colorProvider = colorProvider;
			this.config = config;
			this.iconMap16 = iconMap16;

			setPosition(OverlayPosition.DYNAMIC);
			setLayer(OverlayLayer.ABOVE_WIDGETS);
			setPriority(OverlayPriority.HIGH);
		}

		void pushDrop(BossDefinition boss, long gainedXp)
		{
			long now = System.currentTimeMillis();

			if (config.combineDrops() && !drops.isEmpty())
			{
				Drop latest = drops.peekFirst();
				if (latest != null && (now - latest.startMs) <= config.combineWindowMs())
				{
					latest.xp += gainedXp;
					latest.boss = boss;
					return;
				}
			}

			drops.addFirst(new Drop(boss, gainedXp, now));
			while (drops.size() > config.maxVisibleDrops())
			{
				drops.removeLast();
			}
		}

		@Override
		public Dimension render(Graphics2D g)
		{
			if (!config.enableXpDrops())
			{
				drops.clear();
				return null;
			}

			Rectangle canvas = client.getCanvas().getBounds();
			if (canvas == null)
			{
				return null;
			}

			long now = System.currentTimeMillis();
			int duration = Math.max(1, config.durationMs());

			drops.removeIf(d -> now - d.startMs > duration);

			int sx = clamp(config.startX(), 0, canvas.width);
			int sy = clamp(config.startY(), 0, canvas.height);
			int ex = clamp(config.endX(), 0, canvas.width);
			int ey = clamp(config.endY(), 0, canvas.height);

			double vx = ex - sx;
			double vy = ey - sy;

			double len = Math.sqrt(vx * vx + vy * vy);
			double px = (len == 0) ? 0 : (-vy / len);
			double py = (len == 0) ? 1 : (vx / len);

			g.setFont(g.getFont().deriveFont(Font.BOLD, 14f));

			int idx = 0;
			for (Drop d : drops)
			{
				float t = (now - d.startMs) / (float) duration;
				t = clamp01(t);

				float eased = 1f - (float) Math.pow(1f - t, 2);

				int x = (int) Math.round(sx + vx * eased);
				int y = (int) Math.round(sy + vy * eased);

				int spacing = Math.max(0, config.stackSpacing());
				x += (int) Math.round(px * idx * spacing);
				y += (int) Math.round(py * idx * spacing);

				float alpha = 1f - t;
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

				Color color = colorProvider.get(d.boss);

				String text = "+" + d.xp;
				if (config.showBossNameInDrop())
				{
					text = d.boss.kcName + " " + text;
				}

				int iconSize = 16;
				int iconGap = 6;
				int textX = x;

				if (config.showBossMarker())
				{
					BufferedImage icon = iconMap16.get(d.boss);
					if (icon != null)
					{
						FontMetrics fm = g.getFontMetrics();
						int ascent = fm.getAscent();
						int textTop = y - ascent;
						int iconY = textTop + Math.max(0, (ascent - iconSize) / 2);

						g.drawImage(icon, x, iconY, null);
						textX = x + iconSize + iconGap;
					}
				}

				// shadow
				g.setColor(new Color(0, 0, 0, Math.min(255, color.getAlpha())));
				g.drawString(text, textX + 1, y + 1);

				// main
				g.setColor(color);
				g.drawString(text, textX, y);

				idx++;
			}

			g.setComposite(AlphaComposite.SrcOver);
			return null;
		}

		private static int clamp(int v, int lo, int hi)
		{
			return Math.max(lo, Math.min(hi, v));
		}

		private static float clamp01(float v)
		{
			return Math.max(0f, Math.min(1f, v));
		}
	}
}
