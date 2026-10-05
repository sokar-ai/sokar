package org.fuin.sokar.daemon;

import static org.fuin.sokar.daemon.SokarDaemon.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.app.GateSupport;
import org.fuin.sokar.app.ReviewText;
import org.fuin.sokar.app.SokarContext;
import org.fuin.sokar.app.TaskControl;
import org.fuin.sokar.app.TaskInventory;
import org.fuin.sokar.gate.GitGate;
import org.fuin.sokar.wire.varlink.VarlinkException;
import org.fuin.sokar.wire.varlink.VarlinkServer;

/**
 * The daemon's methods for the gate: what is waiting, its review and the decision.
 * <p>
 * One class per area, so a change to one area's methods is a change to its own file; {@link SokarDaemon} only
 * says which areas there are.
 * <p>
 * The one crossing where work leaves the machine. These are thin over GitGate, which is
 * what the CLI drives too, so an approval means the same thing from either.
 */
final class GateMethods {

    private GateMethods() {
    }

    /**
     * Registers this area's methods.
     *
     * @param server Where.
     * @param context The machine.
     * @param inventory The daemon's one task inventory.
     * @param control The daemon's one task control.
     */
    @SuppressWarnings("unused")
    static void register(VarlinkServer server, SokarContext context, TaskInventory inventory, TaskControl control) {
        server.method("Pending", (parameters, replies) -> {
            final GitGate gate = gate(parameters, context);
            final java.time.Instant now = java.time.Instant.now();
            final List<Map<String, Object>> waiting = gate.pendingDetail().stream().map(push -> {
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", push.name());
                row.put("commit", push.commit());
                row.put("subject", push.subject());
                row.put("waiting", push.lagText(now));
                row.put("at", push.at().toString());
                // Read in a person's own clone, over their ssh: the line to paste. Nothing runs the work.
                row.put("fetch", org.fuin.sokar.app.WaitingFetch.of(gate.mirror(), push.name()));
                return row;
            }).toList();
            replies.last(Map.of("mirror", gate.mirror().toString(),
                    "mode", gate.mode().name().toLowerCase(),
                    "seededFrom", gate.seededFrom() == null ? "" : gate.seededFrom(),
                    "pending", waiting));
        });

        server.method("Review", (parameters, replies) -> {
            final GitGate gate = gate(parameters, context);
            final String name = text(parameters, "name");
            final String against = text(parameters, "against");
            final org.fuin.sokar.gate.ReviewRanking.Review review =
                    gate.rankedReview(name, against.isEmpty() ? null : against);
            final Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("diff", review.patch());
            reply.put("log", gate.log(name, against.isEmpty() ? null : against));
            reply.put("files", review.files().stream().map(file -> {
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", file.path());
                row.put("status", file.status());
                row.put("added", file.added());
                row.put("removed", file.removed());
                row.put("rank", file.rank().name());
                row.put("reason", file.reason());
                return row;
            }).toList());
            final ReviewText.Instruction asked = ReviewText.instruction(context.paths(),
                    GateSupport.project(projectFile(parameters, context)).name(), name);
            if (asked.found()) {
                reply.put("asked", asked.prompt() == null ? "" : asked.prompt());
            }
            replies.last(reply);
        });

        server.method("Approve", (parameters, replies) -> {
            // The single call that sends anything anywhere, and it makes the caller name where.
            final GitGate gate = gate(parameters, context);
            final String branch = text(parameters, "branch");
            if (branch.isEmpty()) {
                throw new VarlinkException(INTERFACE + ".BranchRequired",
                        Map.of("name", text(parameters, "name")));
            }
            try {
                if (flag(parameters, "signed")) {
                    org.fuin.sokar.app.GateSupport.approveSigned(gate, text(parameters, "name"), branch,
                            empty(parameters, "commit"));
                } else {
                    gate.approve(text(parameters, "name"), branch, empty(parameters, "commit"));
                }
            } catch (org.fuin.sokar.gate.GateException.MovedSinceReview ex) {
                // Typed, so an interface says it in its own words and offers the review again.
                throw new VarlinkException(INTERFACE + ".MovedSinceReview", Map.of("name", text(parameters, "name"),
                        "reviewed", ex.reviewed(), "now", ex.now()));
            }
            replies.last(Map.of("forwarded", text(parameters, "name"), "branch", branch));
        });

        // For work merged elsewhere: the interface takes it as a bundle, a person merges and signs it on their own
        // computer and pushes it, and Landed clears it once the upstream holds it (decided by the operator,
        // 2026-09-30). Nothing is signed or pushed here.
        server.method("PendingBundle", (parameters, replies) -> {
            final GitGate gate = gate(parameters, context);
            final String branch = text(parameters, "branch").isEmpty() ? "main" : text(parameters, "branch");
            final java.nio.file.Path scratch;
            try {
                // In a directory of its own, owner-only: a file made and then deleted in the shared /tmp freed its
                // name, and anyone could take it - or its '.lock' - before git wrote there.
                scratch = java.nio.file.Files.createTempDirectory("sokar-pending-").resolve("pending.bundle");
            } catch (java.io.IOException ex) {
                throw new VarlinkException(INTERFACE + ".Failed", Map.of("message", String.valueOf(ex.getMessage())));
            }
            try {
                final long size = gate.bundle(text(parameters, "name"), branch, scratch);
                if (size > GitGate.BUNDLE_LIMIT) {
                    throw new VarlinkException(INTERFACE + ".BundleTooLarge",
                            Map.of("bytes", size, "limit", GitGate.BUNDLE_LIMIT));
                }
                replies.last(Map.of("bundle", java.util.Base64.getEncoder().encodeToString(
                        java.nio.file.Files.readAllBytes(scratch)), "bytes", size));
            } catch (java.io.IOException ex) {
                throw new VarlinkException(INTERFACE + ".Failed", Map.of("message", String.valueOf(ex.getMessage())));
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(scratch);
                    java.nio.file.Files.deleteIfExists(scratch.getParent());
                } catch (java.io.IOException ex) {
                    // A temporary file left behind harms nothing.
                }
            }
        });

        server.method("Landed", (parameters, replies) -> {
            final GitGate gate = gate(parameters, context);
            final String branch = text(parameters, "branch");
            if (branch.isEmpty()) {
                throw new VarlinkException(INTERFACE + ".BranchRequired", Map.of("name", text(parameters, "name")));
            }
            final GitGate.Landing landing = gate.landed(text(parameters, "name"), branch);
            replies.last(Map.of("landed", landing.landed(), "commit", landing.commit(), "detail", landing.detail()));
        });

        server.method("Reject", (parameters, replies) -> {
            final GitGate gate = gate(parameters, context);
            gate.reject(text(parameters, "name"));
            // The task the work came from is told, reason or not: an agent whose work silently vanished hands it
            // over again.
            boolean told;
            try {
                told = org.fuin.sokar.app.PersonNote.rejectedWork(context,
                        org.fuin.sokar.app.GateSupport.byName(context, text(parameters, "project")),
                        text(parameters, "name"), empty(parameters, "reason"));
            } catch (java.io.IOException ex) {
                told = false;
            }
            replies.last(Map.of("rejected", text(parameters, "name"), "told", told));
        });
    }
}
