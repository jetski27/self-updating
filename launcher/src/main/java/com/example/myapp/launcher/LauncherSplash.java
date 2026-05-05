package com.example.myapp.launcher;

import javax.swing.*;
import java.awt.*;

/**
 * Minimal Swing splash window shown by the launcher during the update +
 * boot phase. The actual UI lives in the running Quarkus app on localhost:8080;
 * until that's up there's nothing for the user to see, so this fills the gap.
 *
 * Created lazily — if anything Swing-related fails (headless, classloader,
 * missing java.desktop module) we silently disable and the launcher still
 * works headless. Never throws to the caller.
 */
final class LauncherSplash {

    private final JFrame frame;
    private final JLabel statusLabel;
    private final JLabel detailLabel;
    private final JProgressBar bar;

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
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}

        frame = new JFrame("MyApp");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setUndecorated(true);
        frame.setResizable(false);
        frame.setAlwaysOnTop(true);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(60, 60, 60), 1),
            BorderFactory.createEmptyBorder(20, 24, 18, 24)));
        root.setBackground(new Color(28, 28, 32));

        JLabel title = new JLabel("MyApp");
        title.setForeground(new Color(240, 240, 240));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));

        statusLabel = new JLabel("Starting…");
        statusLabel.setForeground(new Color(220, 220, 220));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 13f));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

        bar = new JProgressBar(0, 100);
        bar.setIndeterminate(true);
        bar.setStringPainted(false);
        bar.setBorderPainted(false);
        bar.setForeground(new Color(80, 160, 240));

        detailLabel = new JLabel(" ");
        detailLabel.setForeground(new Color(150, 150, 150));
        detailLabel.setFont(detailLabel.getFont().deriveFont(Font.PLAIN, 11f));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(title, BorderLayout.WEST);

        JPanel center = new JPanel(new GridLayout(0, 1, 0, 6));
        center.setOpaque(false);
        center.add(statusLabel);
        center.add(bar);
        center.add(detailLabel);

        root.add(header, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        frame.setContentPane(root);

        frame.pack();
        frame.setSize(Math.max(frame.getWidth(), 420), frame.getHeight());
        frame.setLocationRelativeTo(null);
    }

    void show() {
        runOnEdt(() -> frame.setVisible(true));
    }

    void hide() {
        runOnEdt(() -> frame.setVisible(false));
    }

    void dispose() {
        runOnEdt(frame::dispose);
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
            bar.setStringPainted(true);
            bar.setString(pct + "%");
            bar.setValue(pct);
        });
    }

    private static void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (Exception ignored) {
                // Swing can be in shutdown; nothing useful we can do.
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
