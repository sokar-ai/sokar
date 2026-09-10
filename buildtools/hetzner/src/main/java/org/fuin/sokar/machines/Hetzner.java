package org.fuin.sokar.machines;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Renting test machines, and making sure none is left behind.
 * <p>
 * <strong>The one thing worth reading first: a server that is not destroyed costs 81 EUR a
 * month</strong>, against 3 cents for the fifteen minutes it is meant to live. So destruction is
 * structural rather than a step at the end - {@link #rent} hands back an {@link AutoCloseable}
 * that deletes, and {@link #sweep} exists because a process killed between two statements cannot
 * clean up after itself.
 * <p>
 * Everything created here is labelled {@code sokar=ci} so the sweep can find it without a list of
 * names to keep in step with reality.
 */
public final class Hetzner implements AutoCloseable {

    /** Applied to everything created here, and how the sweep finds it again. */
    static final String LABEL_SELECTOR = "sokar=ci";

    /** Identifies the run that created a server, so a run can delete its own and only its own. */
    static final String RUN_LABEL = "run";

    /**
     * Where a CI server may be created.
     * <p>
     * A zone rather than a location on purpose: a single location runs out - fsn1 offered zero
     * server types on 2026-09-06 while nbg1 and hel1 offered eighteen - and a hard-coded one
     * fails with "unsupported location for server type", which reads like a wrong type or a bad
     * token rather than a full datacentre.
     */
    private static final String NETWORK_ZONE = "eu-central";

    /**
     * How long to keep asking when the project is at its server limit, and how often.
     * <p>
     * Four repositories rent machines now and nothing coordinates them, so overlapping runs
     * collide. The API says {@code resource_limit_exceeded} and the run dies twenty minutes in,
     * having already built everything.
     */
    private static final Duration LIMIT_WAIT = Duration.ofSeconds(30);

    /** How many times to wait out a full project before giving up. */
    private static final int LIMIT_ATTEMPTS = 20;

    /** How long a create or delete may take before something is wrong with it. */
    private static final Duration ACTION_PATIENCE = Duration.ofMinutes(5);

    private final Api api;

    private final String runId;

    /** How long to wait between attempts at a full project. Shortened by tests, never in use. */
    private final Duration limitWait;

    private Hetzner(Api api, String runId, Duration limitWait) {
        this.api = api;
        this.runId = runId;
        this.limitWait = limitWait;
    }

    /**
     * Opens the API with a token.
     *
     * @param token The API token, from the environment and never from an argument.
     * @param runId Identifies this run, so its servers can be told from another run's.
     * @return The API.
     */
    public static Hetzner with(String token, String runId) {
        return new Hetzner(new Api(token), runId, LIMIT_WAIT);
    }

    /**
     * Points at another API root, for a test that stands one up.
     *
     * @param base The API root, without a trailing slash.
     * @param runId Identifies this run.
     * @return The API.
     */
    static Hetzner against(String base, String runId) {
        return against(base, runId, LIMIT_WAIT);
    }

    /**
     * Points at another API root and does not really wait, for a test of the waiting.
     *
     * @param base The API root, without a trailing slash.
     * @param runId Identifies this run.
     * @param limitWait How long to wait between attempts.
     * @return The API.
     */
    static Hetzner against(String base, String runId, Duration limitWait) {
        return new Hetzner(new Api("test-token", base), runId, limitWait);
    }

    /**
     * Creates a server and hands back something that deletes it.
     * <p>
     * The delete is in the {@code close}, so a caller using try-with-resources cannot forget it
     * and cannot skip it by failing early. That is the whole reason this returns a resource rather
     * than a server.
     *
     * @param spec What to create.
     * @return The rented machine, which must be closed.
     * @throws IOException If it cannot be created.
     */
    public Rental rent(Spec spec) throws IOException {
        final Map<String, Object> snapshot = newestSnapshot(spec.os());
        final String location = locationFor(spec.serverType());
        final long key = keyMatching(spec.credential());

        System.out.println("creating " + spec.name() + ": " + spec.serverType() + ", "
                + Values.text(snapshot, "description") + ", " + location);
        final Map<String, Object> created = createWhenThereIsRoom(spec, snapshot, location, key);
        final Map<String, Object> server = Values.object(created, "server");
        final long id = Values.id(server.get("id"));
        await(Values.object(created, "action"));

        final String address = Values.text(
                Values.object(Values.object(server, "public_net"), "ipv4"), "ip");
        System.out.println("created  " + spec.name() + " at " + address);
        return new Rental(this, id, spec, address);
    }

    private Map<String, Object> createWhenThereIsRoom(Spec spec, Map<String, Object> snapshot,
            String location, long key) throws IOException {
        final Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("sokar", "ci");
        labels.put(RUN_LABEL, runId);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", spec.name());
        body.put("server_type", spec.serverType());
        body.put("image", Values.id(snapshot.get("id")));
        body.put("location", location);
        body.put("ssh_keys", List.of(key));
        body.put("labels", labels);
        body.put("start_after_create", Boolean.TRUE);

        for (int attempt = 1; attempt <= LIMIT_ATTEMPTS; attempt++) {
            try {
                return api.post("/servers", body);
            } catch (Api.ApiException ex) {
                // Only that one error is retried. Anything else - a bad image, a full datacentre,
                // a rejected token - is a mistake that waiting cannot fix, and burning ten minutes
                // before reporting it would be worse than failing now.
                if (!"resource_limit_exceeded".equals(ex.code())) {
                    throw ex;
                }
                if (attempt == LIMIT_ATTEMPTS) {
                    throw new IOException("the project was still at its server limit after "
                            + LIMIT_ATTEMPTS * limitWait.toSeconds() / 60 + " minutes. Another "
                            + "run is holding machines, or something leaked one: check with the "
                            + "sweep.", ex);
                }
                System.out.println("  at the project's server limit, waiting "
                        + limitWait.toSeconds() + "s (" + attempt + "/" + LIMIT_ATTEMPTS + ")");
                sleep(limitWait);
            }
        }
        throw new IOException("unreachable");
    }

    /**
     * Waits for an action to finish, because "created" is not "ready".
     *
     * @param action The action the API returned.
     * @throws IOException If it failed, or took longer than anything reasonable.
     */
    private void await(Map<String, Object> action) throws IOException {
        if (action.isEmpty()) {
            return;
        }
        final long id = Values.id(action.get("id"));
        final Instant deadline = Instant.now().plus(ACTION_PATIENCE);
        String status = Values.text(action, "status");
        while ("running".equals(status)) {
            if (Instant.now().isAfter(deadline)) {
                throw new IOException("action " + id + " was still running after "
                        + ACTION_PATIENCE.toMinutes() + " minutes");
            }
            sleep(Duration.ofSeconds(2));
            status = Values.text(Values.object(api.get("/actions/" + id), "action"), "status");
        }
        if (!"success".equals(status)) {
            throw new IOException("action " + id + " ended as '" + status + "'");
        }
    }

    /**
     * Returns the most recent snapshot for one operating system.
     * <p>
     * By label rather than by id, so a workflow does not carry a number that goes stale the next
     * time a snapshot is rebuilt. Newest wins, because rebuilding is how an image is updated.
     *
     * @param os The {@code os} label a snapshot was built with, such as {@code fedora}.
     * @return The snapshot.
     * @throws IOException If there is none, saying which were built.
     */
    Map<String, Object> newestSnapshot(String os) throws IOException {
        final List<Map<String, Object>> available = new ArrayList<>();
        for (final Map<String, Object> image : api.all(
                "/images?type=snapshot&label_selector=" + LABEL_SELECTOR + ",os=" + os, "images")) {
            if ("available".equals(Values.text(image, "status"))) {
                available.add(image);
            }
        }
        if (available.isEmpty()) {
            final TreeSet<String> built = new TreeSet<>();
            for (final Map<String, Object> image : api.all(
                    "/images?type=snapshot&label_selector=" + LABEL_SELECTOR, "images")) {
                built.add(Values.labels(image).getOrDefault("os", "?"));
            }
            throw new IOException("No snapshot for '" + os + "'. Built: "
                    + (built.isEmpty() ? "none" : built)
                    + ". Build one with the snapshot provisioner before running a leg.");
        }
        return available.stream().max(Comparator.comparing(Values::created)).orElseThrow();
    }

    /**
     * Picks a location in the zone that can actually create this server type right now.
     * <p>
     * Asked rather than assumed: availability is per datacentre and changes, and Hetzner reports a
     * location that cannot serve a type the same way it reports a nonsense one.
     *
     * @param serverType Type the server will be created with.
     * @return Location name.
     * @throws IOException If nothing in the zone has it, listing what was asked.
     */
    String locationFor(String serverType) throws IOException {
        final List<Map<String, Object>> types = api.all("/server_types?name=" + serverType,
                "server_types");
        if (types.isEmpty()) {
            throw new IOException("no such server type: " + serverType);
        }
        final long wanted = Values.id(types.getFirst().get("id"));

        final List<String> tried = new ArrayList<>();
        for (final Map<String, Object> datacenter : api.all("/datacenters", "datacenters")) {
            final Map<String, Object> location = Values.object(datacenter, "location");
            if (!NETWORK_ZONE.equals(Values.text(location, "network_zone"))) {
                continue;
            }
            boolean has = false;
            for (final Object each : (List<?>) Values.object(datacenter, "server_types")
                    .getOrDefault("available", List.of())) {
                has = has || Values.id(each) == wanted;
            }
            tried.add(Values.text(datacenter, "name") + "=" + (has ? "yes" : "no"));
            if (has) {
                System.out.println("location " + Values.text(location, "name") + " has "
                        + serverType + " (" + String.join(", ", tried) + ")");
                return Values.text(location, "name");
            }
        }
        throw new IOException("no location in " + NETWORK_ZONE + " currently offers " + serverType
                + ": " + String.join(", ", tried));
    }

    /**
     * Returns the project's key that matches the private key in hand.
     * <p>
     * Matched by fingerprint rather than by name. Naming it means keeping two things in step - the
     * secret holding the private half and the key registered in the project - and when they drift
     * the server is created with a public key nobody holds, which shows up as a connection refused
     * twenty lines later. The fingerprint cannot drift.
     *
     * @param credential The private key that will be used to connect.
     * @return The id of the matching key in the project.
     * @throws IOException If none matches, listing what the project holds.
     */
    long keyMatching(Credential credential) throws IOException {
        final String wanted = Fingerprint.md5(credential);
        final List<Map<String, Object>> available = api.all("/ssh_keys", "ssh_keys");
        final List<String> held = new ArrayList<>();
        for (final Map<String, Object> key : available) {
            if (wanted.equals(Values.text(key, "fingerprint"))) {
                System.out.println("ssh key '" + Values.text(key, "name")
                        + "' matches the private key in hand");
                return Values.id(key.get("id"));
            }
            held.add(Values.text(key, "name") + "=" + Values.text(key, "fingerprint"));
        }
        throw new IOException("No key in the project matches the private key (" + wanted + ")."
                + " In the project: " + (held.isEmpty() ? "none" : held)
                + ". Add its public half to the project.");
    }

    /**
     * Deletes one server.
     *
     * @param id Its id.
     * @throws IOException If the API refuses.
     */
    void delete(long id) throws IOException {
        await(Values.object(api.delete("/servers/" + id), "action"));
    }

    /**
     * Returns every server this labelling knows about.
     *
     * @return What the project is holding.
     * @throws IOException If the API refuses.
     */
    public List<Server> servers() throws IOException {
        final List<Server> found = new ArrayList<>();
        for (final Map<String, Object> body : api.all("/servers?label_selector=" + LABEL_SELECTOR,
                "servers")) {
            found.add(new Server(Values.id(body.get("id")), Values.text(body, "name"),
                    Values.labels(body).getOrDefault(RUN_LABEL, ""), Values.created(body),
                    Values.text(Values.object(Values.object(body, "public_net"), "ipv4"), "ip")));
        }
        return found;
    }

    /**
     * Deletes the servers this run created, and only those.
     * <p>
     * Deleting by age instead would catch another run's server whenever that run is slower than
     * the window, and the symptom - ssh dying part way through a build - is close to undebuggable.
     *
     * @return How many were deleted.
     * @throws IOException If the API refuses.
     */
    public int deleteMine() throws IOException {
        int deleted = 0;
        for (final Server server : servers()) {
            if (server.run().equals(runId)) {
                System.out.println("deleting " + server.name());
                delete(server.id());
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * Deletes servers older than a window, whoever created them.
     * <p>
     * This is the net under everything else: a process killed between two statements cannot clean
     * up after itself, and what it leaves behind bills until somebody notices.
     *
     * @param olderThan How old a server must be to count as left behind.
     * @param dryRun Whether to only say what would go.
     * @return How many were deleted, or would have been.
     * @throws IOException If the API refuses.
     */
    public int sweep(Duration olderThan, boolean dryRun) throws IOException {
        final Instant cutoff = Instant.now().minus(olderThan);
        int swept = 0;
        for (final Server server : servers()) {
            if (server.created().isAfter(cutoff)) {
                continue;
            }
            System.out.println((dryRun ? "would delete " : "deleting ") + server.name()
                    + " (run " + server.run() + ", created " + server.created() + ")");
            if (!dryRun) {
                delete(server.id());
            }
            swept++;
        }
        return swept;
    }

    private static void sleep(Duration duration) throws IOException {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting", ex);
        }
    }

    @Override
    public void close() {
        api.close();
    }

    /**
     * A server the project is holding.
     *
     * @param id Its id.
     * @param name Its name.
     * @param run The run that created it, or an empty string.
     * @param created When it was created.
     * @param address Its address.
     */
    public record Server(long id, String name, String run, Instant created, String address) {
    }
}
