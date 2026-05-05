package com.example.myapp.launcher;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.net.URL;

/**
 * Modern splash window shown by the launcher during the update + boot phase.
 *
 * Visual design:
 *   - Undecorated, rounded-corner JFrame (520×260).
 *   - FlatDark L&F so the progress bar / labels look 2024-era.
 *   - Pulsing Azry logo on the left, app title + "by Azry" on the right.
 *   - Soft fade-in on first show, fade-out on hide.
 *   - Status line, thin animated progress bar, muted detail line beneath.
 *
 * Created lazily via {@link #createOrNull()} — if anything Swing-related
 * fails (headless, classloader, missing java.desktop module) we silently
 * disable and the launcher falls back to a fully headless flow. Never throws
 * to the caller.
 */
final class LauncherSplash {

    private static final Color BG = new Color(0x1E1F22);
    private static final Color BG_CARD = new Color(0x2B2D30);
    private static final Color FG_TITLE = new Color(0xF5F5F5);
    private static final Color FG_STATUS = new Color(0xE0E0E0);
    private static final Color FG_DETAIL = new Color(0x9DA0A4);
    private static final Color ACCENT = new Color(0x4E89F2);

    private static final int LOGO_PX = 64;

    private final JFrame frame;
    private final JLabel statusLabel;
    private final JLabel detailLabel;
    private final JProgressBar bar;
    private final PulsingLogo pulsingLogo;

    private Timer fadeTimer;

    static LauncherSplash createOrNull() {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        try {
            return runOnEdtAndReturn(LauncherSplash::new);
        } catch (Throwable t) {
            return null;
        }
    }

    private LauncherSplash() {
        try {
            FlatDarkLaf.setup();
            UIManager.put("ProgressBar.arc", 999);
        } catch (Throwable ignored) {
            // Fall through to whatever default L&F is available.
        }

        frame = new JFrame("PoS Agent");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setUndecorated(true);
        frame.setResizable(false);
        frame.setAlwaysOnTop(true);
        frame.setBackground(BG);
        try { frame.setOpacity(0f); } catch (Exception ignored) { /* graphics device may not support translucency */ }

        BufferedImage logo = loadLogo();
        pulsingLogo = (logo != null) ? new PulsingLogo(logo, LOGO_PX) : null;

        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(BG_CARD);
        root.setBorder(BorderFactory.createEmptyBorder(26, 28, 22, 28));

        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setOpaque(false);
        if (pulsingLogo != null) header.add(pulsingLogo, BorderLayout.WEST);

        JPanel titles = new JPanel();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.setOpaque(false);

        JLabel title = new JLabel("PoS Agent");
        title.setForeground(FG_TITLE);
        title.setFont(deriveFont(Font.BOLD, 24f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("by Azry");
        subtitle.setForeground(FG_DETAIL);
        subtitle.setFont(deriveFont(Font.PLAIN, 12f));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));

        titles.add(Box.createVerticalGlue());
        titles.add(title);
        titles.add(subtitle);
        titles.add(Box.createVerticalGlue());
        header.add(titles, BorderLayout.CENTER);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);

        statusLabel = new JLabel("Starting…");
        statusLabel.setForeground(FG_STATUS);
        statusLabel.setFont(deriveFont(Font.PLAIN, 14f));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));

        bar = new JProgressBar(0, 100);
        bar.setIndeterminate(true);
        bar.setStringPainted(false);
        bar.setBorderPainted(false);
        bar.setForeground(ACCENT);
        bar.setBackground(new Color(0x383A3F));
        bar.setPreferredSize(new Dimension(0, 6));
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        bar.putClientProperty("JProgressBar.largeHeight", false);
        bar.putClientProperty("JProgressBar.square", false);

        detailLabel = new JLabel(" ");
        detailLabel.setForeground(FG_DETAIL);
        detailLabel.setFont(deriveFont(Font.PLAIN, 11f));
        detailLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        detailLabel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        body.add(statusLabel);
        body.add(bar);
        body.add(detailLabel);

        root.add(header, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.setSize(520, 260);
        frame.setLocationRelativeTo(null);

        try {
            frame.setShape(new RoundRectangle2D.Float(0, 0, frame.getWidth(), frame.getHeight(), 18, 18));
        } catch (Exception ignored) {
            // PERPIXEL_TRANSPARENT not supported — frame will be a plain rectangle.
        }
    }

    void show() {
        runOnEdt(() -> {
            frame.setVisible(true);
            if (pulsingLogo != null) pulsingLogo.start();
            fade(true);
        });
    }

    void hide() {
        runOnEdt(() -> fade(false));
    }

    void dispose() {
        runOnEdt(() -> {
            stopFade();
            if (pulsingLogo != null) pulsingLogo.stop();
            frame.dispose();
        });
    }

    void setStatus(String text) {
        runOnEdt(() -> statusLabel.setText(text));
    }

    void setDetail(String text) {
        runOnEdt(() -> detailLabel.setText(text == null || text.isEmpty() ? " " : text));
    }

    void setIndeterminate() {
        runOnEdt(() -> {
            bar.setIndeterminate(true);
            bar.setStringPainted(false);
        });
    }

    void setProgress(float fraction) {
        int pct = Math.max(0, Math.min(100, Math.round(fraction * 100f)));
        runOnEdt(() -> {
            if (bar.isIndeterminate()) bar.setIndeterminate(false);
            bar.setStringPainted(false);
            bar.setValue(pct);
            String hint = currentDetailHint();
            detailLabel.setText(hint.isEmpty() ? pct + "%" : hint + "  ·  " + pct + "%");
        });
    }

    private String currentDetailHint() {
        String t = detailLabel.getText();
        if (t == null) return "";
        int sep = t.indexOf("  ·  ");
        return sep < 0 ? t.trim() : t.substring(0, sep).trim();
    }

    private void fade(boolean in) {
        stopFade();
        try {
            float[] alpha = {frame.getOpacity()};
            float target = in ? 1f : 0f;
            float step = in ? 0.08f : -0.10f;
            fadeTimer = new Timer(16, e -> {
                alpha[0] = clamp(alpha[0] + step, 0f, 1f);
                try { frame.setOpacity(alpha[0]); } catch (Exception ignored) {}
                if ((in && alpha[0] >= target) || (!in && alpha[0] <= target)) {
                    ((Timer) e.getSource()).stop();
                    if (!in) frame.setVisible(false);
                }
            });
            fadeTimer.start();
        } catch (Exception ignored) {
            try { frame.setOpacity(in ? 1f : 0f); } catch (Exception e2) { /* nothing */ }
            if (!in) frame.setVisible(false);
        }
    }

    private void stopFade() {
        if (fadeTimer != null && fadeTimer.isRunning()) fadeTimer.stop();
        fadeTimer = null;
    }

    private static BufferedImage loadLogo() {
        try {
            URL url = LauncherSplash.class.getResource("/azry.png");
            return url == null ? null : ImageIO.read(url);
        } catch (Exception e) {
            return null;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static Font deriveFont(int style, float size) {
        Font seg = new Font("Segoe UI", style, Math.round(size));
        if (seg.getFamily().equalsIgnoreCase("Segoe UI")) return seg;
        Font def = UIManager.getFont("Label.font");
        return def != null ? def.deriveFont(style, size) : new Font(Font.SANS_SERIF, style, Math.round(size));
    }

    private static void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (Exception ignored) {
                // EDT may be shutting down; nothing useful we can do.
            }
        }
    }

    private static <T> T runOnEdtAndReturn(java.util.function.Supplier<T> s) {
        if (SwingUtilities.isEventDispatchThread()) return s.get();
        Object[] holder = new Object[1];
        Throwable[] err = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { holder[0] = s.get(); } catch (Throwable t) { err[0] = t; }
            });
        } catch (Exception e) {
            return null;
        }
        if (err[0] != null) return null;
        @SuppressWarnings("unchecked")
        T t = (T) holder[0];
        return t;
    }

    /**
     * Pulsing logo: oscillates alpha (0.55 → 1.0) and scale (0.95 → 1.05) on a
     * sine wave so the logo "breathes" while the launcher is working. Source
     * image is kept at original resolution and downsampled at paint time so it
     * stays crisp on HiDPI displays.
     */
    private static final class PulsingLogo extends JComponent {
        private final BufferedImage image;
        private final int sizePx;
        private float phase = 0f;
        private final Timer timer;

        PulsingLogo(BufferedImage image, int sizePx) {
            this.image = image;
            this.sizePx = sizePx;
            Dimension d = new Dimension(sizePx + 8, sizePx + 8);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
            setOpaque(false);
            timer = new Timer(33, e -> {
                phase += 0.07f;
                if (phase > Math.PI * 2) phase -= (float) (Math.PI * 2);
                repaint();
            });
        }

        void start() { if (!timer.isRunning()) timer.start(); }
        void stop()  { if (timer.isRunning()) timer.stop(); }

        @Override
        protected void paintComponent(Graphics g) {
            if (image == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            float wave = (float) ((Math.sin(phase) + 1.0) / 2.0); // 0..1
            float alpha = 0.55f + 0.45f * wave;                   // 0.55..1.0
            float scale = 0.95f + 0.10f * wave;                   // 0.95..1.05

            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            int dim = Math.round(sizePx * scale);
            int x = cx - dim / 2;
            int y = cy - dim / 2;
            g2.drawImage(image, x, y, dim, dim, null);

            g2.dispose();
        }
    }
}
