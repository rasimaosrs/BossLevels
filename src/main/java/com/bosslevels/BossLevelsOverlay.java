package com.bosslevels;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

@FunctionalInterface
interface BossColorProvider
{
    Color get(BossDefinition boss);
}

public class BossLevelsOverlay extends Overlay
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

    public BossLevelsOverlay(
            Client client,
            BossColorProvider colorProvider,
            BossLevelsConfig config,
            Map<BossDefinition, BufferedImage> iconMap16
    )
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

            // ease-out
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
