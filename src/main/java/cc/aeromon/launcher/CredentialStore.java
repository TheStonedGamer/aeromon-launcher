package cc.aeromon.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;

/** Small OS-backed secret store for the Microsoft OAuth refresh token. */
final class CredentialStore {
    private CredentialStore() {}

    static String load(Path home) throws Exception {
        return switch (platform()) {
            case WINDOWS -> windowsRead(home);
            case MAC -> commandRead("security", "find-generic-password", "-s", service(home), "-a", "microsoft", "-w");
            case LINUX -> commandRead("secret-tool", "lookup", "service", service(home), "account", "microsoft");
            case OTHER -> null;
        };
    }

    static void save(Path home, String token) throws Exception {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Microsoft refresh token is empty");
        switch (platform()) {
            case WINDOWS -> windowsWrite(home, token);
            case MAC -> macWrite(home, token);
            case LINUX -> runWithInput(token, "secret-tool", "store", "--label=Aeromon Microsoft sign-in", "service", service(home), "account", "microsoft");
            case OTHER -> throw new IOException("Remembering Microsoft sign-in is not supported on this operating system");
        }
    }

    static void clear(Path home) throws Exception {
        switch (platform()) {
            case WINDOWS -> windowsClear(home);
            case MAC -> commandWrite("security", "delete-generic-password", "-s", service(home), "-a", "microsoft");
            case LINUX -> commandWrite("secret-tool", "clear", "service", service(home), "account", "microsoft");
            case OTHER -> { }
        }
    }

    private static String service(Path home) {
        return "Aeromon-" + Integer.toUnsignedString(home.toAbsolutePath().normalize().toString().hashCode(), 16);
    }

    private enum Platform { WINDOWS, MAC, LINUX, OTHER }
    private static Platform platform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return Platform.WINDOWS;
        if (os.contains("mac")) return Platform.MAC;
        if (os.contains("linux")) return Platform.LINUX;
        return Platform.OTHER;
    }

    private static String windowsRead(Path home) throws Exception {
        Path file = windowsFile(home);
        if (!Files.isRegularFile(file)) return null;
        String encoded = Files.readString(file, StandardCharsets.US_ASCII).trim();
        if (encoded.isEmpty()) return null;
        return unprotect(Base64.getDecoder().decode(encoded));
    }

    private static void windowsWrite(Path home, String token) throws Exception {
        Path file = windowsFile(home);
        Files.createDirectories(file.getParent());
        byte[] protectedToken = protect(token);
        Path staged = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(staged, Base64.getEncoder().encodeToString(protectedToken), StandardCharsets.US_ASCII,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try { Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static void windowsClear(Path home) throws IOException { Files.deleteIfExists(windowsFile(home)); }
    private static Path windowsFile(Path home) { return home.resolve("launcher/microsoft-refresh-token.dpapi"); }

    private static void macWrite(Path home, String token) throws Exception {
        String service = service(home);
        String script = "import Foundation; import Security; " +
            "let q:[String:Any]=[kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:\"" + service + "\",kSecAttrAccount as String:\"microsoft\"]; " +
            "let d=FileHandle.standardInput.readDataToEndOfFile(); SecItemDelete(q as CFDictionary); var a=q; a[kSecValueData as String]=d; " +
            "let s=SecItemAdd(a as CFDictionary,nil); if s != errSecSuccess { fputs(\"Keychain save failed: \\(s)\\n\",stderr); exit(1) }";
        runWithInput(token, "swift", "-e", script);
    }

    private static byte[] protect(String token) throws Exception {
        String value = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        String script = "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; Add-Type -AssemblyName System.Security;" +
            "$p=[Convert]::FromBase64String('" + value + "');" +
            "$c=[Security.Cryptography.ProtectedData]::Protect($p,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser);" +
            "[Console]::Write([Convert]::ToBase64String($c))";
        return Base64.getDecoder().decode(runPowerShell(script).trim());
    }

    private static String unprotect(byte[] protectedToken) throws Exception {
        String value = Base64.getEncoder().encodeToString(protectedToken);
        String script = "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; Add-Type -AssemblyName System.Security;" +
            "$c=[Convert]::FromBase64String('" + value + "');" +
            "$p=[Security.Cryptography.ProtectedData]::Unprotect($c,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser);" +
            "[Console]::Write([Convert]::ToBase64String($p))";
        return new String(Base64.getDecoder().decode(runPowerShell(script).trim()), StandardCharsets.UTF_8);
    }

    private static String runPowerShell(String script) throws Exception {
        Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand",
            Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Windows credential protection timed out"); }
        String text = new String(output, StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0 || text.isBlank()) throw new IOException("Windows credential protection failed");
        return text;
    }

    private static String commandRead(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Credential store timed out"); }
        if (process.exitValue() != 0) return null;
        String value = new String(output, StandardCharsets.UTF_8).strip();
        return value.isEmpty() ? null : value;
    }

    private static void commandWrite(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Credential store timed out"); }
        if (process.exitValue() != 0) {
            String detail = new String(output, StandardCharsets.UTF_8).strip();
            throw new IOException(detail.isEmpty() ? "OS credential store command failed" : detail);
        }
    }

    private static void runWithInput(String input, String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); stdin.write('\n'); }
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Credential store timed out"); }
        if (process.exitValue() != 0) {
            String detail = new String(output, StandardCharsets.UTF_8).strip();
            throw new IOException(detail.isEmpty() ? "OS credential store command failed" : detail);
        }
    }
}
