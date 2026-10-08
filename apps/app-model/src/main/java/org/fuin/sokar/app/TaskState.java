package org.fuin.sokar.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.wire.Json;

/**
 * The part of a task's state that is knowledge about the task, kept where a reboot does not reach it.
 * <p>
 * <strong>One rule for where a file lives</strong>: what describes something live - a socket, a pid file,
 * a log - is volatile and stays in the runtime directory; what is knowledge about the task is durable. The
 * runtime directory is still where everything is used from: the container's annotation and mounts name its
 * paths, fixed when the container was created. So the durable files are <em>copies</em>, saved whenever they
 * change and put back when a reboot left the runtime directory empty - never a second place anything is read
 * from while the task runs.
 * <p>
 * <strong>Nothing secret is saved here.</strong> The resume record is saved without the gate token, and the
 * phantom provider token's file is not saved at all; both are in the vault ({@link TaskSecrets}).
 */
public final class TaskState {

    /**
     * The durable files: what the task is, how its helpers are started, the work it held when it stopped, and
     * the egress it was given - the resolver's files, the firewall ruleset, the hooks' description and the
     * run-scope grants - which cannot be rebuilt exactly from anything else.
     */
    static final List<String> DURABLE = List.of(org.fuin.sokar.wire.Sidecar.RECORDED_ID, "task.json", TaskInventory.CREDENTIALS_FILE, TaskInventory.GRANTS_FILE, TaskHelpers.FILE, UnhandedWork.FILE, "dns.conf",
            TaskRunner.HAND_IN_LIMIT_FILE, "dnsmasq.servers", "ruleset.nft", "sidecar.json", "granted", "granted-addresses");

    /** The environment entry of the gate helper that is a secret, and is kept in the vault instead. */
    static final String GATE_TOKEN = "SOKAR_GATE_TOKEN";

    private final SokarContext context;

    /**
     * Constructor.
     *
     * @param context Where the paths come from.
     */
    public TaskState(SokarContext context) {
        this.context = context;
    }

    /**
     * Saves a task's durable files as they are now. Never fails the caller: a task whose state could not be saved
     * runs, and only its return after a reboot is lost.
     *
     * @param container The task's container.
     */
    public void save(String container) {
        final Path runtime = context.paths().tasks().containerState(container);
        if (!Files.isDirectory(runtime)) {
            return;
        }
        final Path durable = context.paths().tasks().taskRecord(container);
        try {
            createPrivate(durable);
            for (final String name : DURABLE) {
                final Path from = runtime.resolve(name);
                final Path to = durable.resolve(name);
                if (!Files.isRegularFile(from)) {
                    Files.deleteIfExists(to);
                    continue;
                }
                final Path written = to.resolveSibling(name + ".new");
                if (TaskHelpers.FILE.equals(name)) {
                    Files.writeString(written, withoutSecrets(Files.readString(from, StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8);
                } else {
                    Files.copy(from, written, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.setPosixFilePermissions(written, PosixFilePermissions.fromString("rw-------"));
                Files.move(written, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException | RuntimeException ex) {
            // Reported by the start after a reboot that finds nothing to bring back, not by the run.
        }
    }

    /**
     * Whether a task's state was saved.
     *
     * @param container The task's container.
     * @return true when there is a description or a resume record to bring it back from
     */
    public boolean saved(String container) {
        final Path durable = context.paths().tasks().taskRecord(container);
        return Files.isRegularFile(durable.resolve("task.json")) || Files.isRegularFile(durable.resolve(TaskHelpers.FILE));
    }

    /**
     * Puts a task's saved files back into its runtime directory, which a reboot emptied.
     *
     * @param container The task's container.
     * @return The runtime directory, filled.
     * @throws IOException If it cannot be made or filled.
     */
    Path restore(String container) throws IOException {
        final Path runtime = context.paths().tasks().containerState(container);
        final Path durable = context.paths().tasks().taskRecord(container);
        createPrivate(runtime);
        for (final String name : DURABLE) {
            final Path from = durable.resolve(name);
            if (Files.isRegularFile(from)) {
                final Path to = runtime.resolve(name);
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                Files.setPosixFilePermissions(to, PosixFilePermissions.fromString("rw-------"));
            }
        }
        return runtime;
    }

    /**
     * Forgets a task's saved state, as removing the task does.
     *
     * @param container The task's container.
     */
    public void forget(String container) {
        final Path durable = context.paths().tasks().taskRecord(container);
        if (!Files.isDirectory(durable)) {
            return;
        }
        try (var walk = Files.walk(durable)) {
            for (final Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("could not forget the saved state of " + container, ex);
        }
    }

    /**
     * Removes the gate token from a resume record's text.
     *
     * @param resume The record as written.
     * @return The same record without the secret.
     */
    static String withoutSecrets(String resume) {
        if (!(Json.parse(resume) instanceof Map<?, ?> document) || !(document.get("helpers") instanceof List<?> helpers)) {
            return resume;
        }
        final List<Object> stripped = new java.util.ArrayList<>();
        for (final Object each : helpers) {
            if (each instanceof Map<?, ?> helper && helper.get("environment") instanceof Map<?, ?> environment) {
                final Map<Object, Object> copy = new LinkedHashMap<>(helper);
                final Map<Object, Object> kept = new LinkedHashMap<>(environment);
                kept.remove(GATE_TOKEN);
                copy.put("environment", kept);
                stripped.add(copy);
            } else {
                stripped.add(each);
            }
        }
        final Map<Object, Object> copy = new LinkedHashMap<>(document);
        copy.put("helpers", stripped);
        return Json.write(copy);
    }

    private static void createPrivate(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
    }

}
