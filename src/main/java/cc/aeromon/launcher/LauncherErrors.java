package cc.aeromon.launcher;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Diagnostic traces contain exception types and source locations, never provider payloads. */
final class LauncherErrors {
    static String describe(Throwable failure) {
        for (Throwable cause=failure; cause!=null; cause=cause.getCause()) {
            String message=cause.getMessage();
            if(message!=null&&!message.isBlank())return message;
        }
        return "The launcher could not complete this action ("+failure.getClass().getSimpleName()+").";
    }
    static Path record(Path home,Throwable failure) {
        Path log=home.resolve("launcher/launcher-errors.log");
        StringBuilder trace=new StringBuilder(Instant.now()+"\n");
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            trace.append(cause.getClass().getName()).append('\n');
            for(StackTraceElement frame:cause.getStackTrace())trace.append("  at ").append(frame).append('\n');
        }
        try {
            Files.createDirectories(log.getParent());
            if(Files.exists(log)&&Files.size(log)>1048576)Files.move(log,log.resolveSibling("launcher-errors.previous.log"),StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(log,trace.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            return log;
        }catch(Exception ignored){return null;}
    }
}
