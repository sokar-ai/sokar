package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.fuin.sokar.vault.SigningKey;
import org.fuin.sokar.vault.SshSignature;

/**
 * Takes what an agent has finished writing out of its outbox, and signs it.
 * <p>
 * The agent writes into {@code outbox/tmp} and renames into {@code outbox/new}, so a message is
 * complete exactly when it appears here and nothing has to guess whether a file is still being
 * written.
 * <p>
 * <strong>Two directories, and the role each may carry is fixed here.</strong> What comes out of
 * the container's outbox may only be {@code ROLE_AGENT}; what a person wrote, which arrives in a
 * directory the container cannot see, may only be {@code ROLE_USER}. The filter accepts both roles
 * on this host's word that they mean what they say, so this is where that word is kept - a task
 * that wrote {@code ROLE_USER} into its outbox would otherwise be forging a message from its own
 * operator.
 * <p>
 * <strong>The signature is made over the bytes as they are</strong>, and nothing downstream may
 * rewrite them - not the filter, not a transport. That is what lets the far end verify with nothing
 * but {@code ssh-keygen} and an {@code allowed_signers} file.
 */
public final class MessageIntake {

    /** What a signature over a message is for, so one cannot be replayed as another. */
    public static final String NAMESPACE = "sokar-message";

    /** The only role a message out of a task's own outbox may carry. */
    public static final String AGENT = "ROLE_AGENT";

    /** The only role a message a person wrote may carry. */
    public static final String PERSON = "ROLE_USER";

    private final SigningKey key;

    /**
     * Constructor with the host's signing key.
     *
     * @param key Key held on the host. Never inside a container.
     */
    public MessageIntake(final SigningKey key) {
        this.key = key;
    }

    /**
     * Takes every finished message out of the outbox and out of the person's directory.
     *
     * @param mailbox The task's mailbox.
     * @return What was taken, in the order it was taken.
     * @throws IOException Reading or moving failed.
     */
    public List<String> take(final Mailbox mailbox) throws IOException {
        final List<String> taken = new ArrayList<>(take(mailbox, mailbox.outboxNew(), false));
        taken.addAll(take(mailbox, mailbox.person(), true));
        return taken;
    }

    /**
     * Takes from one directory.
     *
     * @param mailbox The task's mailbox.
     * @param from Where to take from.
     * @param person Whether this is the directory only a person writes into.
     * @return What was taken.
     * @throws IOException Reading or moving failed.
     */
    private List<String> take(final Mailbox mailbox, final Path from, final boolean person)
            throws IOException {
        final List<String> taken = new ArrayList<>();
        for (final Path message : finished(from)) {
            final String name = message.getFileName().toString();
            final byte[] bytes = Files.readAllBytes(message);
            // Only the false claim is stopped here, not every wrong role: a message with no role
            // or a nonsense one is the filter's to refuse, and it answers the sender properly.
            // What the filter cannot judge is whether a task is entitled to say a person wrote
            // this, and that is exactly what this decides.
            final String role = MessageFile.role(message);
            if (person ? !PERSON.equals(role) : PERSON.equals(role)) {
                // Held, not corrected: a host that rewrote the role would be signing its own
                // sentence about who wrote this, and the record would stop being evidence.
                Files.move(message, mailbox.hold().resolve(name),
                        StandardCopyOption.REPLACE_EXISTING);
                continue;
            }

            // The signature is written before the message is moved. A crash between the two leaves
            // a signature with no message, which the next run overwrites; the other order would
            // leave a message with no signature, and nothing downstream could tell whether it had
            // been signed or tampered with.
            final Path signature = mailbox.incoming().resolve(name + ".sig");
            final Path partial = mailbox.incoming().resolve("." + name + ".sig.tmp");
            Files.writeString(partial, SshSignature.sign(key, bytes, NAMESPACE),
                    StandardCharsets.UTF_8);
            Files.move(partial, signature, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            Files.move(message, mailbox.incoming().resolve(name),
                    StandardCopyOption.ATOMIC_MOVE);
            taken.add(name);
        }
        return taken;
    }

    private List<Path> finished(final Path outbox) throws IOException {
        if (!Files.isDirectory(outbox)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(outbox)) {
            return entries.filter(Files::isRegularFile)
                    // A dot file is the agent's business, and anything without the suffix is not a
                    // message: neither is taken, and neither is reported as refused.
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }
}
