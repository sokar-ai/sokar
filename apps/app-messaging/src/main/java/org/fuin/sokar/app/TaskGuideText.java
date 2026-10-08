package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.fuin.sokar.runtime.Containerfile;

/**
 * What a task's agent is told about Sokar in its task: its handed-in files, the builds of its pushes, and its mailbox
 * where it has one.
 * <p>
 * Nothing told an agent that {@code /sokar/files} existed, so it pushed and never looked at its red build unless a
 * person remembered to say so. <strong>The same for every task of one Sokar version</strong>, as
 * the mailbox's guide is and for the same reason: an agent takes it into its system prompt, which a provider caches by
 * its beginning. The files' part comes first, since every task has {@code /sokar/files}; the mailbox's follows it.
 */
public final class TaskGuideText {

    private TaskGuideText() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the guide, as its agent is told.
     *
     * @param mailbox Whether the task has a mailbox, whose guide then follows.
     * @return Markdown.
     */
    public static String text(final boolean mailbox) {
        final String files = """
                # Sokar in this task

                This task runs in Sokar. What follows is what Sokar gives you here besides your workspace:
                files handed to you, the builds of what you push, and a mailbox where the task has one.

                **When your work is done, commit it and push it with `git push sokar`, and name no branch.** The push
                goes to this task's own place, `$SOKAR_TASK_REF`, at the gate on this machine: where a person reviews
                it, or, in an online project, from where it goes on at once to this task's own branch at the forge -
                never onto a branch you name. A person takes back only what you pushed; what is only in your
                workspace stays in this task.

                **When you are told the repository you work from moved on, `git fetch sokar` brings it.**
                Rebase or merge your work onto it before you push again.

                # Files handed to you

                - **`@FILES@` holds the files handed to this task**, by a person or by Sokar, and nothing
                  else. A file appears there whole, never half written.
                - You can read them but not change or remove them. Copy one into your workspace to change it.
                - A file handed in again under the same name replaces the one before.
                - **Look there when you start, and whenever you come to rest.** When a file arrives while you
                  wait at your prompt, a line may appear there naming it; not every agent is given one.

                ## The builds of what you push

                Where the project follows its builds, Sokar watches what the forge builds for each commit you
                push to the task's branch, and hands in what it did:

                - `build-<commit>.txt`, `<commit>` the first 12 characters of the commit: lines of
                  `key: value` - `commit`, `verdict`, one `job` line per job, `since`, and `detail` when
                  there is more to say.
                - `verdict` is `queued` or `running` while the build goes on, then `success`, `failure` or
                  `cancelled`. It is `unknown` when there is no build or Sokar cannot read it, and `detail`
                  says why.
                - A `job` line names the job and what became of it, and the file holding the end of its log
                  when one was handed in: `build-<commit>-<n>.log`. A failed job's log always is, when the
                  forge has one.
                - The file is replaced each time the verdict changes.

                **After you push, read `build-<commit>.txt` until its verdict is final, before you call the work
                done.** On `failure`, read the log each failed `job` line names, fix the cause, and push again.
                """.replace("@FILES@", Containerfile.HAND_IN_FILES);
        return mailbox ? files + "\n" + MailboxGuide.text() : files;
    }

    /**
     * Writes the guide into its directory, made when missing, and only when it changed.
     *
     * @param guide Where it goes.
     * @param mailbox Whether the task has a mailbox.
     * @throws IOException Writing failed.
     */
    public static void write(final TaskGuide guide, final boolean mailbox) throws IOException {
        final Path directory = guide.directory();
        if (Files.isSymbolicLink(directory)) {
            throw new IOException(directory + " is a link; the guide is not written through it");
        }
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
        final String text = text(mailbox);
        final Path file = guide.file();
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                && Files.readString(file, StandardCharsets.UTF_8).equals(text)) {
            return;
        }
        // Beside the mounted directory and renamed into it, so the agent never reads half of it.
        final Path staged = Files.createTempFile(directory.getParent(), ".guide", ".tmp");
        Files.writeString(staged, text, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rw-r--r--"));
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
