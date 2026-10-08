package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.runtime.Podman;
import org.fuin.sokar.wire.Sidecar;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HandInsTest {

    private static final String TASK = "sokar-demo-shell-1";

    private static final String RUN = "0123456789ab";

    @TempDir
    Path dir;

    /** The container's filesystem, as far as the commands HandIns runs reach it. */
    private Path container;

    private TaskPaths paths;

    private final List<List<String>> ran = new ArrayList<>();

    private Instant now = Instant.parse("2026-10-06T10:00:00Z");

    private HandIns handIns;

    @BeforeEach
    void aRunningTask() throws IOException {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        paths = new SokarPaths(xdg, dir.resolve("bin")).tasks();
        Files.createDirectories(paths.containerState(TASK));
        Sidecar.recordId(paths.containerState(TASK), RUN);
        container = dir.resolve("container");
        Files.createDirectories(container.resolve("sokar/files"));
        Files.createDirectories(container.resolve("sokar/.incoming"));
        handIns = handIns();
    }

    private HandIns handIns() {
        final Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        return new HandIns(paths, new Podman(command -> {
            throw new AssertionError("nothing is run through podman's own runner");
        }), this::inContainer, clock);
    }

    /** dd, chmod, mv and rm as they act on the container's files, run as root after 'exec ... --user root TASK'. */
    private int inContainer(final List<String> arguments, final @Nullable Path stdin) throws IOException {
        ran.add(arguments);
        final List<String> command = arguments.subList(arguments.indexOf(TASK) + 1, arguments.size());
        switch (command.get(0)) {
            case "dd" -> Files.copy(stdin, inside(command.get(1).substring("of=".length())),
                    StandardCopyOption.REPLACE_EXISTING);
            case "chmod" -> {
                // Permissions are root's business in the container; the fake has none to set.
            }
            case "mv" -> Files.move(inside(command.get(2)), inside(command.get(3)), StandardCopyOption.REPLACE_EXISTING);
            case "rm" -> Files.deleteIfExists(inside(command.get(2)));
            default -> throw new AssertionError("unexpected " + command);
        }
        return 0;
    }

    private Path inside(final String path) {
        return container.resolve(path.substring(1));
    }

    private static byte[] bytes(final int size) {
        final byte[] content = new byte[size];
        for (int i = 0; i < size; i++) {
            content[i] = (byte) (i * 31 + 7);
        }
        return content;
    }

    private String sha256(final byte[] content) throws IOException {
        final Path file = Files.write(dir.resolve("content"), content);
        return HandIns.sha256Of(file);
    }

    @Test
    void aSmallFileInOnePartLandsInTheTaskAndIsWrittenDown() throws IOException {
        final byte[] content = "the build log\n".getBytes(StandardCharsets.UTF_8);

        final HandIns.Progress progress = handIns.part(TASK, "build.log", content.length, sha256(content), 0,
                content, "michi");

        assertThat(progress.received()).isEqualTo(content.length);
        assertThat(progress.file()).isNotNull();
        assertThat(container.resolve("sokar/files/build.log")).hasBinaryContent(content);
        assertThat(container.resolve("sokar/.incoming")).isEmptyDirectory();
        assertThat(handIns.summary(TASK).files()).singleElement().satisfies(file -> {
            assertThat(file.name()).isEqualTo("build.log");
            assertThat(file.bytes()).isEqualTo(content.length);
            assertThat(file.by()).isEqualTo("michi");
            assertThat(file.run()).isEqualTo(RUN);
            assertThat(file.at()).isEqualTo("2026-10-06T10:00:00Z");
        });
        assertThat(handIns.record(TASK)).extracting(HandIns.Entry::event).containsExactly(HandIns.GIVEN);
    }

    @Test
    void aFileInPartsAppearsOnlyWhenTheLastPartHasArrived() throws IOException {
        final byte[] content = bytes(10_000);
        final String sha = sha256(content);

        final HandIns.Progress first = handIns.part(TASK, "big.bin", content.length, sha, 0,
                Arrays.copyOfRange(content, 0, 4_000), "michi");

        assertThat(first.received()).isEqualTo(4_000);
        assertThat(first.file()).isNull();
        assertThat(container.resolve("sokar/files/big.bin")).doesNotExist();
        assertThat(ran).as("nothing reaches the task before it is complete").isEmpty();

        handIns.part(TASK, "big.bin", content.length, sha, 4_000, Arrays.copyOfRange(content, 4_000, 9_000), "michi");
        final HandIns.Progress last = handIns.part(TASK, "big.bin", content.length, sha, 9_000,
                Arrays.copyOfRange(content, 9_000, 10_000), "michi");

        assertThat(last.file()).isNotNull();
        assertThat(container.resolve("sokar/files/big.bin")).hasBinaryContent(content);
    }

    @Test
    void aPartOutOfOrderSaysWhereToGoOn() throws IOException {
        final byte[] content = bytes(1_000);
        final String sha = sha256(content);
        handIns.part(TASK, "x.bin", content.length, sha, 0, Arrays.copyOfRange(content, 0, 300), "michi");

        assertThatThrownBy(() -> handIns.part(TASK, "x.bin", content.length, sha, 600,
                Arrays.copyOfRange(content, 600, 1_000), "michi"))
                .isInstanceOfSatisfying(HandIns.Refused.class, refused -> {
                    assertThat(refused.error()).isEqualTo("PartOutOfOrder");
                    assertThat(refused.parameters()).containsEntry("received", 300L).containsEntry("name", "x.bin");
                });

        // Resumed where it said, it completes.
        assertThat(handIns.part(TASK, "x.bin", content.length, sha, 300, Arrays.copyOfRange(content, 300, 1_000),
                "michi").file()).isNotNull();
    }

    @Test
    void aFirstPartThatDoesNotStartAtZeroIsOutOfOrderToo() {
        assertThatThrownBy(() -> handIns.part(TASK, "x.bin", 10, "0".repeat(64), 5, new byte[5], "michi"))
                .isInstanceOfSatisfying(HandIns.Refused.class, refused -> {
                    assertThat(refused.error()).isEqualTo("PartOutOfOrder");
                    assertThat(refused.parameters()).containsEntry("received", 0L);
                });
    }

    @Test
    void aSecondFileOfTheSameNameWhileOneIsInFlightIsRefused() throws IOException {
        final byte[] content = bytes(1_000);
        handIns.part(TASK, "x.bin", content.length, sha256(content), 0, Arrays.copyOfRange(content, 0, 100), "michi");

        final byte[] other = bytes(2_000);
        final String otherSha = sha256(other);
        assertThatThrownBy(() -> handIns.part(TASK, "x.bin", other.length, otherSha, 0,
                Arrays.copyOfRange(other, 0, 100), "someone"))
                .isInstanceOfSatisfying(HandIns.Refused.class, refused -> {
                    assertThat(refused.error()).isEqualTo("HandInInProgress");
                    assertThat(refused.parameters()).containsEntry("received", 100L).containsEntry("bytes", 1_000L);
                });
    }

    @Test
    void aHandInNobodyContinuesIsDroppedAfterTenMinutes() throws IOException {
        final byte[] content = bytes(1_000);
        handIns.part(TASK, "x.bin", content.length, sha256(content), 0, Arrays.copyOfRange(content, 0, 100), "michi");
        try (var parts = Files.list(paths.containerState(TASK).resolveSibling("hand-in").resolve(TASK))) {
            for (final Path part : parts.toList()) {
                Files.setLastModifiedTime(part, java.nio.file.attribute.FileTime.from(now));
            }
        }
        now = now.plus(Duration.ofMinutes(11));

        final byte[] other = bytes(2_000);
        assertThat(handIns.part(TASK, "x.bin", other.length, sha256(other), 0, other, "someone").file())
                .as("the abandoned one is gone, so a new one starts").isNotNull();
        assertThat(container.resolve("sokar/files/x.bin")).hasBinaryContent(other);
    }

    @Test
    void aFileOverTheLimitIsRefusedBeforeAnyByteIsKept() throws IOException {
        Files.writeString(paths.containerState(TASK).resolve(HandIns.LIMIT_FILE), "1000");

        assertThatThrownBy(() -> handIns.part(TASK, "big.bin", 1_001, "0".repeat(64), 0, new byte[10], "michi"))
                .isInstanceOfSatisfying(HandIns.Refused.class, refused -> {
                    assertThat(refused.error()).isEqualTo("FileTooLarge");
                    assertThat(refused.parameters()).containsEntry("bytes", 1_001L).containsEntry("limit", 1_000L);
                });
        assertThat(paths.containerState(TASK).resolveSibling("hand-in")).doesNotExist();
        assertThat(handIns.summary(TASK).limit()).isEqualTo(1_000L);
    }

    @Test
    void theLimitIsTheDefaultWhereTheTaskRecordsNone() {
        assertThat(handIns.summary(TASK).limit()).isEqualTo(64L * 1024 * 1024);
    }

    @Test
    void contentThatDoesNotHashToWhatWasSaidIsNotPlaced() throws IOException {
        final byte[] content = bytes(500);

        assertThatThrownBy(() -> handIns.part(TASK, "x.bin", content.length, "0".repeat(64), 0, content, "michi"))
                .isInstanceOfSatisfying(HandIns.Refused.class, refused -> {
                    assertThat(refused.error()).isEqualTo("FileDiffers");
                    assertThat(refused.parameters()).containsEntry("expected", "0".repeat(64));
                });
        assertThat(container.resolve("sokar/files/x.bin")).doesNotExist();
        assertThat(handIns.record(TASK)).isEmpty();
    }

    @Test
    void aNameThatIsNotAPlainFileNameIsRefused() {
        for (final String name : List.of("", "../etc/passwd", "a/b", ".hidden", "line\nbreak", "x".repeat(256))) {
            assertThatThrownBy(() -> HandIns.checkName(name)).as("'%s'", name)
                    .isInstanceOfSatisfying(HandIns.Refused.class,
                            refused -> assertThat(refused.error()).isEqualTo("FileNameRefused"));
        }
        HandIns.checkName("spec v2 (final).pdf");
    }

    @Test
    void aSecondFileOfTheSameNameReplacesTheFirstAndIsTakenBackOnce() throws IOException {
        final byte[] first = "one".getBytes(StandardCharsets.UTF_8);
        final byte[] second = "two".getBytes(StandardCharsets.UTF_8);
        handIns.part(TASK, "spec.txt", first.length, sha256(first), 0, first, "michi");
        handIns.part(TASK, "spec.txt", second.length, sha256(second), 0, second, "michi");

        assertThat(container.resolve("sokar/files/spec.txt")).hasBinaryContent(second);
        assertThat(handIns.summary(TASK).files()).singleElement()
                .satisfies(file -> assertThat(file.sha256()).isEqualTo(sha256(second)));

        handIns.takeBack(TASK, "spec.txt", "michi");

        assertThat(container.resolve("sokar/files/spec.txt")).doesNotExist();
        assertThat(handIns.summary(TASK).files()).isEmpty();
        assertThat(handIns.record(TASK)).extracting(HandIns.Entry::event)
                .containsExactly(HandIns.GIVEN, HandIns.REPLACED, HandIns.TAKEN_BACK);
        assertThatThrownBy(() -> handIns.takeBack(TASK, "spec.txt", "michi"))
                .isInstanceOfSatisfying(HandIns.Refused.class,
                        refused -> assertThat(refused.error()).isEqualTo("NoSuchFile"));
    }

    @Test
    void theRecordOutlivesTheTaskAndTellsTwoRunsOfOneNameApart() throws IOException {
        final byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        handIns.part(TASK, "a.txt", content.length, sha256(content), 0, content, "michi");

        // The task is removed: its runtime state goes. A later task gets the same name and a new container.
        deleteTree(paths.containerState(TASK));
        Files.createDirectories(paths.containerState(TASK));
        Sidecar.recordId(paths.containerState(TASK), "ba9876543210");

        assertThat(handIns.summary(TASK).files()).as("the new run was handed nothing").isEmpty();
        assertThat(handIns.record(TASK)).singleElement()
                .satisfies(entry -> assertThat(entry.file().run()).isEqualTo(RUN));
        assertThat(Files.readString(paths.paths().xdg().state().resolve("hand-ins").resolve(TASK + ".jsonl")))
                .as("the record names the file, never its content").doesNotContain("\"x\"")
                .contains("\"name\":\"a.txt\"");
    }

    @Test
    void aWholeFileFromThisMachineIsPlacedTheSameWay() throws IOException {
        final Path source = Files.write(dir.resolve("screenshot.png"), bytes(3_000));

        final HandIns.HandedFile file = handIns.give(TASK, "screenshot.png", source, "michi");

        assertThat(container.resolve("sokar/files/screenshot.png")).hasSameBinaryContentAs(source);
        assertThat(file.sha256()).isEqualTo(HandIns.sha256Of(source));
        assertThat(ran).anySatisfy(command -> assertThat(command).contains("--user", "root", "dd"));
    }

    private static void deleteTree(final Path root) throws IOException {
        try (var tree = Files.walk(root)) {
            for (final Path path : tree.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
