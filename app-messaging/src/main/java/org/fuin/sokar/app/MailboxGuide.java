package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.project.Mail;
import org.fuin.sokar.wire.Json;

/**
 * What a task's agent is told about its mailbox: how it works, and whom it can reach.
 * <p>
 * Nothing told a running agent that its mailbox existed (the operator, 2026-10-04), so messages between tasks worked
 * only when a person explained them by hand. Two files in the box, both written by the host and only read by the agent:
 * <ul>
 * <li>{@link Mailbox#GUIDE}, how it works. <strong>The same for every task of one Sokar version</strong>: an agent
 * takes it into its system prompt, and a provider caches a prompt by its beginning, so a text that changed between two
 * starts of one session would have the whole conversation read again, uncached (Agent Smith, 2026-10-04). It says only
 * what the host and the filter enforce, from their own constants, so it cannot promise what they refuse.</li>
 * <li>{@link Mailbox#CARD}, whom the task can reach right now: written again at every pass, read by the agent before
 * it writes. Names only - a task never learns where a peer is.</li>
 * </ul>
 */
public final class MailboxGuide {

    private MailboxGuide() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns how a task's mailbox works, as its agent is told.
     *
     * @return Markdown.
     */
    public static String text() {
        final String mail = Mailbox.MOUNT;
        return """
                # Your mailbox

                This task has a mailbox at `@MAIL@`. Through it you take part in your project's conversation:
                with the other tasks of your project and with the people in it. Nothing else in this task can
                reach them.

                ## Who reads what

                - **Everything said in the project's conversation reaches every task of the project**, you
                  included: what people write there and what other tasks send. You read all of it.
                - **A message meant for you names you** in `metadata.to`: a person writes `@` and your task's
                  name, or answers a message of yours. Act on what names you.
                - **A person's message in the room that names nobody is meant for every agent in the room:
                  answer it in the room.** It has `"via": "room"` and an empty `metadata.to`.
                - A message that names another is not yours to answer, and another task's message that names
                  nobody is the conversation around you, there to know.
                - **A person can also write to you directly**, in a chat with you alone. Such a message has
                  `"via": "direct"` in its `metadata` and is always meant for you.
                - **Answer where you were asked.** A person who types in your terminal is answered in your
                  terminal, never with a message. A message is answered with a message, by copying `via`
                  from the message you answer into yours.
                  A person's message from the room is answered to them by name with `"via": "room"`: it goes
                  into the room and mentions them. A direct message is answered to them by name with
                  `"via": "direct"`: it goes into your direct chat with them. A task's message is answered to
                  that task.
                - When a message for you arrives while you wait at your prompt, a line appears there saying so.

                ## Whom you can write to

                `@CARD@` lists whom you can reach right now, by name. **Read it before you write**: it changes
                while you work, as other tasks start and stop.
                - `@PEOPLE@` is everybody in your project's conversation.
                - A person's own name (as `metadata.from` shows it on their messages) reaches them: in the
                  room, mentioned, or with `"via": "direct"` in your direct chat with them. Write to them
                  directly only to answer a direct message, or when you are asked to.
                - Another task's name reaches that task, and the conversation sees it too.

                ## Receiving

                - A message appears in `@MAIL@/inbox/new/`, one JSON file each.
                - Look there when you start, between steps of your work, and before you finish.
                - Once you have read a message, move it to `@MAIL@/inbox/cur/` (`mv`), so you do not read it
                  twice.
                - A message of yours that was refused or could not be delivered comes back into
                  `inbox/new/`, with the reason in its text.

                ## Sending

                Write the message to `@MAIL@/outbox/tmp/<name>.json`, then move it into
                `@MAIL@/outbox/new/` with `mv`. Never write into `outbox/new/` directly: a half-written file
                there would be taken as it is. Keep the file readable (the default); the host picks it up,
                signs it and delivers it.

                A message is one JSON object (A2A 1.0):

                ```json
                {
                  "messageId": "a-unique-id-of-yours",
                  "role": "@ROLE@",
                  "parts": [ { "text": "What you have to say." } ],
                  "metadata": { "to": "michi", "via": "room" }
                }
                ```

                - `messageId`: required, unique among your messages.
                - `role`: always `"@ROLE@"`, spelled exactly so.
                - `metadata.to`: exactly one name from `@CARD@`. A message to nobody, or to more than one, is
                  held.
                - `metadata.via`: `"room"` or `"direct"`, as in the message you answer; `"room"` when you
                  write first.
                - `contextId` is optional: keep the one of the message you answer, to keep a conversation
                  together.
                - Nothing else is needed: no `extensions`, no `kind` at the top level.

                ## What does not travel

                Every message you send is checked before it leaves, and only plain English prose passes.
                Encoded data is refused, also when cut into pieces by spaces or line breaks: base64, hex, a full
                commit id and the like. So are `data`, `raw` and `url` parts. Long code, identifiers and command
                lines may be refused. **Never put a secret or a credential into a message:** do not rely on the
                check to catch one. **Work travels by commit**, through the task's gate, never inside a message.
                Name the commit by its short form (7 to 12 characters), never the full id. Depending on the
                project, a person may read a message before it goes.
                """.replace("@MAIL@", mail).replace("@CARD@", Mailbox.CARD).replace("@PEOPLE@", Mail.PEOPLE)
                .replace("@ROLE@", MessageIntake.AGENT);
    }

    /**
     * Writes both files into a task's box, each only when it changed.
     *
     * @param mailbox The task's mailbox, made already.
     * @param mail Whom the task can reach: its project's peers, its people and its other tasks.
     * @throws IOException Writing failed.
     */
    public static void write(final Mailbox mailbox, final Mail mail) throws IOException {
        mailbox.refuseLinks();
        put(mailbox, mailbox.guide(), text());
        final List<Map<String, Object>> peers = new ArrayList<>();
        for (final Mail.Peer peer : mail.peers()) {
            final Map<String, Object> each = new LinkedHashMap<>();
            each.put("name", peer.name());
            each.put("is", Mail.PEOPLE.equals(peer.name()) && peer.conversation()
                    ? "the people in your project's conversation"
                    : Mail.Peer.VOUCHED.equals(peer.trust()) && peer.conversation() ? "another task of your project"
                    : peer.destination().startsWith("@") ? "a person in your project's conversation, in a direct chat"
                    : "a peer your project names");
            peers.add(each);
        }
        put(mailbox, mailbox.card(), Json.write(Map.of("peers", peers)) + "\n");
    }

    private static void put(final Mailbox mailbox, final Path file, final String text) throws IOException {
        if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && Files.readString(file, StandardCharsets.UTF_8).equals(text)) {
            return;
        }
        // Beside it and renamed, so the agent never reads half of it; readable by the agent, writable only here.
        final Path staged = Files.createTempFile(mailbox.root(), ".guide", ".tmp");
        Files.writeString(staged, text, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rw-r--r--"));
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
