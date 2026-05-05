package com.example.myapp.launcher;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Modern splash window shown by the launcher during the update + boot phase.
 *
 * Visual design:
 *   - Undecorated, rounded-corner JFrame (480×220).
 *   - FlatLaf "FlatDark" L&F so the progress bar / labels look 2024-era.
 *   - Soft fade-in on first show, fade-out on hide.
 *   - Large status line, thin animated progress bar with percent overlay,
 *     muted detail line beneath.
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

    private final JFrame frame;
    private final JLabel statusLabel;
    private final JLabel detailLabel;
    private final JProgressBar bar;

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
            UIManager.put("ProgressBar.selectionBackground", FG_TITLE);
            UIManager.put("ProgressBar.selectionForeground", FG_TITLE);
        } catch (Throwable ignored) {
            // Fall through to whatever default L&F is available.
        }

        frame = new JFrame("MyApp");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setUndecorated(true);
        frame.setResizable(false);
        frame.setAlwaysOnTop(true);
        frame.setBackground(BG);
        try { frame.setOpacity(0f); } catch (Exception ignored) { /* graphics device may not support translucency */ }

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(BG_CARD);
        root.setBorder(BorderFactory.createEmptyBorder(28, 32, 26, 32));

        JLabel title = new JLabel("MyApp");
        title.setForeground(FG_TITLE);
        title.setFont(deriveFont(Font.BOLD, 22f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("Self-updating desktop app");
        subtitle.setForeground(FG_DETAIL);
        subtitle.setFont(deriveFont(Font.PLAIN, 11f));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setBorder(BorderFactory.createEmptyBorder(2, 0, 22, 0));

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

        root.add(title);
        root.add(subtitle);
        root.add(statusLabel);
        root.add(bar);
        root.add(detailLabel);

        frame.setContentPane(root);
        frame.setSize(480, 220);
        frame.setLocationRelativeTo(null);

        // Rounded corners. Win11 rounds undecorated windows automatically via
        // DWM, but setting a shape works on Win10/older too and gives a
        // consistent radius across versions.
        try {
            frame.setShape(new RoundRectangle2D.Float(0, 0, frame.getWidth(), frame.getHeight(), 18, 18));
        } catch (Exception ignored) {
            // PERPIXEL_TRANSPARENT not supported — frame will be a plain rectangle.
        }
    }

    void show() {
        runOnEdt(() -> {
            frame.setVisible(true);
            fade(true);
        });
    }

    void hide() {
        runOnEdt(() -> fade(false));
    }

    void dispose() {
        runOnEdt(() -> {
            stopFade();
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
            detailLabel.setText(detailHint() + "  ·  " + pct + "%");
        });
    }

    private String detailHint() {
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
            // Translucency not supported — fall back to instant show/hide.
            try { frame.setOpacity(in ? 1f : 0f); } catch (Exception e2) { /* nothing */ }
            if (!in) frame.setVisible(false);
        }
    }

    private void stopFade() {
        if (fadeTimer != null && fadeTimer.isRunning()) fadeTimer.stop();
        fadeTimer = null;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static Font deriveFont(int style, float size) {
        // Prefer Segoe UI on Windows for that modern look; fall back to L&F default.
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
}
