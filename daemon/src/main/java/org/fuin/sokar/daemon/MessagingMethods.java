package org.fuin.sokar.daemon;

import static org.fuin.sokar.daemon.SokarDaemon.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.app.SokarContext;
import org.fuin.sokar.app.TaskControl;
import org.fuin.sokar.app.TaskInventory;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.wire.varlink.VarlinkException;
import org.fuin.sokar.wire.varlink.VarlinkServer;

/**
 * The daemon's methods for messages, their conversation and their moderation.
 * <p>
 * One class per area, so a change to one area's methods is a change to its own file; {@link SokarDaemon} only
 * says which areas there are.
 */
final class MessagingMethods {

    private MessagingMethods() {
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
        server.method("MessageKey", (parameters, replies) -> replies.last(messageKey(context)));

        // The five message verbs. They are the same objects 'sokar talk' shows, through the same
        // classes, so an interface and a terminal cannot disagree about what a mailbox holds.
        server.method("Peers", (parameters, replies) -> {
            final org.fuin.sokar.app.Mailbox mailbox = mailboxOf(context, parameters);
            final org.fuin.sokar.core.project.Project project = org.fuin.sokar.core.project.ProjectReader.read(
                    projectFile(parameters, context));
            final org.fuin.sokar.app.Moderation moderation =
                    org.fuin.sokar.app.Moderation.of(context, project);
            final java.util.List<Map<String, Object>> peers = new java.util.ArrayList<>();
            // With the project's other tasks, as the pass delivers to them: the contract says whom this task may
            // address, and listing only the file's peers hid the tasks it reaches by name.
            final org.fuin.sokar.core.project.Mail mail =
                    org.fuin.sokar.app.MessageRelease.peersOf(context, text(parameters, "task"));
            // Counted the way the budget counts, so what a client shows is what the next pass enforces.
            final org.fuin.sokar.app.MessageBudget budget =
                    new org.fuin.sokar.app.MessageBudget(new org.fuin.sokar.app.MessageRecord(mailbox), mail);
            for (final org.fuin.sokar.core.project.Mail.Peer peer : mail.peers()) {
                final org.fuin.sokar.app.Moderation.Peer state = moderation.peer(peer.name());
                final Map<String, Object> each = new LinkedHashMap<>();
                each.put("name", peer.name());
                each.put("address", peer.address());
                each.put("trust", peer.trust());
                each.put("perDay", peer.perDay());
                each.put("sentToday", budget.sentToday(peer.name()));
                each.put("receivedToday", budget.receivedToday(peer.name()));
                each.put("mode", state.mode());
                each.put("held", state.held());
                peers.add(each);
            }
            replies.last(Map.of("peers", peers));
        });

        server.method("Talk", (parameters, replies) -> {
            if (!replies.streaming()) {
                throw new VarlinkException(INTERFACE + ".StreamRequired", Map.of("method", "Talk"));
            }
            // Every mailbox on the machine, from the records themselves: what happened to a
            // message is written down as it happens, so following the record is following the
            // conversation without a second copy of it that could disagree.
            final Map<String, Integer> seen = new java.util.HashMap<>();
            boolean first = true;
            try {
                while (replies.open()) {
                    for (final org.fuin.sokar.app.Mailbox mailbox
                            : new org.fuin.sokar.app.Mailboxes(context.paths()).all()) {
                        final String task = mailbox.root().getFileName().toString();
                        final java.util.List<Map<String, Object>> lines =
                                new org.fuin.sokar.app.MessageRecord(mailbox).entries();
                        if (first) {
                            // Live only, as the contract says: what was there before a client listened is not news.
                            seen.put(task, lines.size());
                            continue;
                        }
                        for (int i = seen.getOrDefault(task, 0); i < lines.size(); i++) {
                            final Map<String, Object> line = lines.get(i);
                            replies.more(Map.of("task", task,
                                    "at", String.valueOf(line.getOrDefault("at", "")),
                                    "event", String.valueOf(line.getOrDefault("event", "")),
                                    "message", String.valueOf(line.getOrDefault("message", "")),
                                    "id", String.valueOf(line.getOrDefault("id", "")),
                                    "peer", String.valueOf(line.getOrDefault("peer", "")),
                                    "detail", String.valueOf(line.getOrDefault("detail", ""))));
                        }
                        seen.put(task, lines.size());
                    }
                    first = false;
                    Thread.sleep(TALK_INTERVAL.toMillis());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        server.method("Say", (parameters, replies) -> {
            final org.fuin.sokar.app.Mailbox mailbox = mailboxOf(context, parameters);
            final String refused = org.fuin.sokar.app.MessageSay.refusal(
                    org.fuin.sokar.app.MessageRelease.peersOf(context, text(parameters, "task")), text(parameters, "peer"));
            if (refused != null) {
                throw new VarlinkException(INTERFACE + ".NoSuchPeer",
                        Map.of("peer", text(parameters, "peer"), "detail", refused));
            }
            final String written = new org.fuin.sokar.app.MessageSay().write(mailbox,
                    text(parameters, "peer"), text(parameters, "kind"),
                    empty(parameters, "context"), text(parameters, "text"));
            // Written, not sent: the next pass puts it through the filter like any other message,
            // and saying "sent" here would promise something this step does not do.
            replies.last(Map.of("message", written, "outcome", "WRITTEN"));
        });

        server.method("Tell", (parameters, replies) -> {
            final org.fuin.sokar.app.Mailbox mailbox = mailboxOf(context, parameters);
            final String said = text(parameters, "text").strip();
            if (said.isEmpty()) {
                throw new VarlinkException(INTERFACE + ".Failed", Map.of("message", "nothing to tell"));
            }
            final String told = new org.fuin.sokar.app.PersonNote().tell(mailbox, said, null);
            // A person's word to this one task: its agent at rest is told it is there (Agent Smith, 2026-10-04).
            new org.fuin.sokar.app.AgentWake(context).announce(mailbox, mailbox.root().getFileName().toString(), null);
            replies.last(Map.of("message", told));
        });

        server.method("ReadHeld", (parameters, replies) -> {
            final org.fuin.sokar.app.MessageRead.Held held = new org.fuin.sokar.app.MessageRead()
                    .read(mailboxOf(context, parameters), text(parameters, "id"));
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("outcome", held.outcome().name());
            answer.put("standing", held.standing());
            answer.put("message", held.message());
            answer.put("id", held.id());
            answer.put("role", held.role());
            answer.put("peer", held.peer());
            answer.put("kind", held.kind());
            answer.put("at", held.at());
            answer.put("text", held.text());
            answer.put("reason", held.reason());
            answer.put("direction", held.direction());
            replies.last(answer);
        });

        server.method("Held", (parameters, replies) -> {
            // One task's mailbox when one is named - an unknown name is NoSuchTask, as everywhere -
            // or every mailbox on the machine.
            final java.util.List<org.fuin.sokar.app.Mailbox> mailboxes = empty(parameters, "task") == null
                    ? new org.fuin.sokar.app.Mailboxes(context.paths()).all()
                    : java.util.List.of(mailboxOf(context, parameters));
            final java.util.List<Map<String, Object>> messages = new java.util.ArrayList<>();
            for (final org.fuin.sokar.app.Mailbox mailbox : mailboxes) {
                for (final org.fuin.sokar.app.MessageRead.Held held : new org.fuin.sokar.app.MessageRead().list(mailbox)) {
                    final Map<String, Object> message = new LinkedHashMap<>();
                    message.put("task", mailbox.root().getFileName().toString());
                    message.put("standing", held.standing());
                    message.put("message", held.message());
                    message.put("id", held.id());
                    message.put("role", held.role());
                    message.put("peer", held.peer());
                    message.put("kind", held.kind());
                    message.put("at", held.at());
                    message.put("reason", held.reason());
                    message.put("direction", held.direction());
                    messages.add(message);
                }
            }
            replies.last(Map.of("messages", messages));
        });

        server.method("Release", (parameters, replies) -> {
            final org.fuin.sokar.app.MessageRelease.Result result =
                    new org.fuin.sokar.app.MessageRelease().because(empty(parameters, "reason"))
                            .enrolledIn(org.fuin.sokar.app.MessageRelease.enrolled(context, text(parameters, "task")))
                            .decide(mailboxOf(context, parameters),
                            text(parameters, "id"), flag(parameters, "refuse"),
                            org.fuin.sokar.app.MessageRelease.peersOf(context, text(parameters, "task")));
            replies.last(Map.of("outcome", result.outcome().name(), "message", result.message(),
                    "id", result.id(), "detail", result.detail()));
        });

        // A person grants an authorization once, in a browser somewhere else. The stream says "needed" with the
        // link, then how it ended: no client polls a consent flow, so no question gets two grants.
        // A person joins a project's conversation with an account made for them. Answered once: the login is
        // in this reply and nowhere else, never kept.
        server.method("SetUpMessages", (parameters, replies) -> {
            final org.fuin.sokar.core.project.Project project = conversational(context, text(parameters, "project"));
            try {
                replies.last(Map.of("messages", org.fuin.sokar.app.Conversations.setUp(context, project)));
            } catch (org.fuin.sokar.app.Conversations.Refused ex) {
                throw new VarlinkException(INTERFACE + ".ConversationRefused",
                        Map.of("message", String.valueOf(ex.getMessage()), "code", ex.code()));
            }
        });

        server.method("JoinMessages", (parameters, replies) -> {
            final org.fuin.sokar.core.project.Project project = conversational(context, text(parameters, "project"));
            final Map<?, ?> said;
            try {
                said = org.fuin.sokar.app.Conversations.join(context, project, text(parameters, "person"),
                        flag(parameters, "reset"));
            } catch (org.fuin.sokar.app.Conversations.Refused ex) {
                if (ex.exists() != null) {
                    throw new VarlinkException(INTERFACE + ".MemberExists",
                            Map.of("person", text(parameters, "person"), "user", ex.exists()));
                }
                throw new VarlinkException(INTERFACE + ".ConversationRefused",
                        Map.of("message", String.valueOf(ex.getMessage()), "code", ex.code()));
            }
            final Map<String, String> login = new LinkedHashMap<>();
            if (said.get("login") instanceof Map<?, ?> given) {
                given.forEach((key, value) -> login.put(String.valueOf(key), String.valueOf(value)));
            }
            replies.last(Map.of("transport", project.mail().conversations().iterator().next(), "login", login,
                    "shown", said.get("shown") instanceof String shown ? shown : ""));
        });

        server.method("MessageMembers", (parameters, replies) -> {
            final org.fuin.sokar.core.project.Project project = conversational(context, text(parameters, "project"));
            replies.last(Map.of("members", org.fuin.sokar.app.Conversations.members(context, project).entrySet()
                    .stream().map(member -> Map.of("person", member.getKey(), "user", member.getValue())).toList()));
        });

        server.method("Moderate", (parameters, replies) -> {
            // Per project, for every task of it: 'task' is no longer needed and is ignored.
            final org.fuin.sokar.core.project.Project project = org.fuin.sokar.core.project.ProjectReader.read(
                    projectFile(parameters, context));
            final org.fuin.sokar.app.Moderation.Change change =
                    org.fuin.sokar.app.Moderation.of(context, project).set(text(parameters, "name"),
                            parameters.get("held") instanceof Boolean held ? held : null,
                            empty(parameters, "mode"), project);
            if (change.peer() == null) {
                throw new VarlinkException(INTERFACE + ".Failed",
                        Map.of("message", change.refused()));
            }
            replies.last(Map.of("name", text(parameters, "name"), "mode", change.peer().mode(),
                    "held", change.peer().held()));
        });
    }
}
