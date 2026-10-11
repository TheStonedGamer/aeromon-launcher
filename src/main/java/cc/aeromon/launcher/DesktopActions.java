package cc.aeromon.launcher;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/** Opens links and folders through the platform's configured desktop handler. */
final class DesktopActions {
    private DesktopActions() {}

    static void browse(URI uri) throws IOException {
        if (openWithDesktop(Desktop.Action.BROWSE, () -> Desktop.getDesktop().browse(uri))) return;
        if (Minecraft.os().equals("linux")) {
            launch("xdg-open", uri.toString());
            return;
        }
        throw new IOException("Opening the system browser is not supported on this platform.");
    }

    static void open(Path path) throws IOException {
        if (openWithDesktop(Desktop.Action.OPEN, () -> Desktop.getDesktop().open(path.toFile()))) return;
        if (Minecraft.os().equals("linux")) {
            launch("xdg-open", path.toAbsolutePath().toString());
            return;
        }
    }

    private static boolean openWithDesktop(Desktop.Action action, DesktopOperation operation) throws IOException {
        try {
            if (!Desktop.isDesktopSupported()) return false;
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(action)) return false;
            operation.run();
            return true;
        } catch (UnsupportedOperationException | java.awt.AWTError ignored) {
            // Try the platform command below when Java's desktop integration is unavailable.
            return false;
        }
    }

    private static void launch(String command, String target) throws IOException {
        try {
            new ProcessBuilder(List.of(command, target)).start();
        } catch (IOException failure) {
            throw new IOException("Could not open the system browser or file manager. Install a desktop opener such as xdg-open and try again.", failure);
        }
    }

    @FunctionalInterface
    private interface DesktopOperation { void run() throws IOException; }
}
