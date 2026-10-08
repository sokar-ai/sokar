package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * The authorizations somebody has to grant before work can use them - a question for a person, not a
 * failure: a credential of a kind a person grants that was never granted, or whose grant has ended at the
 * service.
 * <p>
 * <strong>One file per credential, so every process that finds one can raise it</strong> - a start refused
 * for want of it, the broker of a running task that finds the grant ended - and the daemon streams them to
 * every interface, so a second person sees what the first was refused (decided by the operator on
 * 2026-09-29). <strong>Raised once per credential until a grant lands:</strong> a second refusal for the same
 * entry keeps the first question; a grant clears it.
 */
public final class AuthorizationsNeeded {

    /** Never granted. */
    public static final String NEVER = "never";

    /** Granted once, and no longer valid at the service: revoked or expired. */
    public static final String ENDED = "ended";

    private AuthorizationsNeeded() {
    }

    /**
     * Raises the question for a credential, unless it is open already for the same reason.
     *
     * @param context Where the state is.
     * @param credential The vault entry that names the service.
     * @param task The task that needed it, or "".
     * @param project That task's project, or "".
     * @param state {@link #NEVER} or {@link #ENDED}.
     */
    public static void raise(SokarContext context, String credential, String task, String project, String state) {
        final Path file = file(context, credential);
        final Map<String, String> open = read(file);
        if (open != null && state.equals(open.get("state"))) {
            return;
        }
        final Map<String, String> question = new LinkedHashMap<>();
        question.put("credential", credential);
        question.put("task", task);
        question.put("project", project);
        question.put("state", state);
        question.put("at", Instant.now().toString());
        try {
            Files.createDirectories(file.getParent());
            final Path staged = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(staged, Json.write(question), StandardCharsets.UTF_8);
            Files.move(staged, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            // The refusal itself is said where it happened; only the question for others is missing.
        }
    }

    /**
     * Clears the question for a credential: a grant landed.
     *
     * @param context Where the state is.
     * @param credential The vault entry.
     */
    public static void clear(SokarContext context, String credential) {
        try {
            Files.deleteIfExists(file(context, credential));
        } catch (IOException ex) {
            // Left open: the next grant or look clears it.
        }
    }

    /**
     * Returns the questions open now.
     *
     * @param context Where the state is.
     * @return Each: {@code credential}, {@code task}, {@code project}, {@code state}, {@code at}.
     */
    public static List<Map<String, String>> open(SokarContext context) {
        final Path directory = directory(context);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        final List<Map<String, String>> open = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().forEach(path -> {
                final Map<String, String> question = read(path);
                if (question != null) {
                    open.add(question);
                }
            });
        } catch (IOException ex) {
            return List.of();
        }
        return open;
    }

    private static @Nullable Map<String, String> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> read) {
                final Map<String, String> question = new LinkedHashMap<>();
                read.forEach((key, value) -> question.put(String.valueOf(key), String.valueOf(value)));
                return question;
            }
        } catch (IOException | RuntimeException ex) {
            // Unreadable: not a question anybody can answer.
        }
        return null;
    }

    private static Path directory(SokarContext context) {
        return context.paths().xdg().state().resolve("authorizations");
    }

    private static Path file(SokarContext context, String credential) {
        return directory(context).resolve(credential.replace('/', '_') + ".json");
    }
}
