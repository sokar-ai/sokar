package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Tests for what somebody sees when they type a command wrongly.
 * <p>
 * The case that prompted this: {@code sokar unlock} answered "Unmatched argument at index 0:
 * 'unlock'" and then suggested {@code sokar doctor}. The wording described the parser's position
 * in an array, and the advice pointed at an unrelated command - because the built-in suggestion
 * only compares against the names at one level, and {@code unlock} lives one level down.
 */
class CliErrorsTest {

    private String run(String... args) {
        final StringWriter err = new StringWriter();
        final CommandLine cmd = new CommandLine(new SokarCli(),
                new SokarFactory(SokarContext.real()));
        cmd.setErr(new PrintWriter(err, true));
        cmd.setOut(new PrintWriter(new StringWriter(), true));
        cmd.setParameterExceptionHandler(CliErrors.handler());
        cmd.execute(args);
        return err.toString();
    }

    @Test
    void aCommandThatLivesOneLevelDownIsFoundRatherThanGuessedAt() {

        // Not a misspelling of anything. Telling somebody where it actually is turns the message
        // into the one thing they can act on.
        assertThat(run("unlock"))
                .contains("has no command called 'unlock'")
                .contains("it is 'sokar vault unlock'");
    }

    @Test
    void aRealTypoIsAnsweredWithTheNearestCommandAnywhereInTheTree() {

        // Searched across the whole tree, which is what the built-in suggestion cannot do:
        // 'improt' is nowhere near any top-level name, and is one edit from 'vault import'.
        assertThat(run("improt")).contains("did you mean 'sokar vault import'?");
        assertThat(run("doktor")).contains("did you mean 'sokar doctor'?");
    }

    @Test
    void aTypoInsideASubcommandIsFoundToo() {

        assertThat(run("vault", "unlok")).contains("did you mean 'sokar vault unlock'?");
    }

    @Test
    void somethingNothingResemblesFallsBackToWhatIsAvailable() {

        // A confident wrong suggestion is worse than none, so nothing close enough means the
        // commands are listed instead.
        final String said = run("zzzzzz");
        assertThat(said).contains("has no command called 'zzzzzz'").contains("try one of:");
        assertThat(said).doesNotContain("did you mean");
    }

    @Test
    void aVeryShortWordIsNotGuessedAt() {

        // At one or two characters almost everything is within two edits of almost everything,
        // and the suggestion would be noise dressed as help. 'ga' is two edits from 'gate',
        // which is exactly the false confidence the length guard exists to refuse - a shorter
        // example would pass whether the guard were there or not.
        assertThat(run("ga")).doesNotContain("did you mean");
    }

    @Test
    void aNameFromElsewhereIsFoundEvenWhenYouAreAlreadyInsideASubcommand() {

        // The search has to start at the top rather than where somebody is standing. Typing
        // 'sokar vault doctor' is a real mistake, and 'doctor' exists - one level up. Searching
        // only the current command finds nothing and falls back to listing.
        assertThat(run("vault", "doctor"))
                .contains("'sokar vault' has no command called 'doctor'")
                .contains("it is 'sokar doctor'");
    }

    @Test
    void anUnknownOptionSaysItIsAnOptionAndNotACommand() {

        assertThat(run("doctor", "--wat"))
                .contains("'--wat' is not an option of 'sokar doctor'")
                .doesNotContain("did you mean");
    }

    @Test
    void everyMessageEndsWithSomethingToRun() {

        // The rule the rest of this product keeps: nothing reports a failure without naming the
        // next action.
        assertThat(run("unlock")).contains("--help");
        assertThat(run("zzzzzz")).contains("--help");
        assertThat(run("doctor", "--wat")).contains("sokar doctor --help");
    }

    @Test
    void nothingSaysUnmatchedArgumentAtIndex() {

        // The phrasing this replaced. It described where the parser was, which is a fact about
        // the parser rather than about what somebody typed.
        assertThat(run("unlock")).doesNotContain("Unmatched argument").doesNotContain("index 0");
    }

    /** Paths rooted in a temporary directory, so a test never writes into the real state. */
    private SokarPaths pathsIn(java.nio.file.Path dir) {
        return new SokarPaths(org.fuin.sokar.core.config.XdgPaths.of(name -> switch (name) {
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            default -> null;
        }, dir), dir.resolve("bin"));
    }

    @Test
    void turnsAnUnhandledFailureIntoOneLine(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {

        // Reported: 'task resume' answered sixteen frames of picocli. The frames say where in
        // this code it happened, which is of no use to the person being told, and they bury the
        // one line that was.
        final StringWriter err = new StringWriter();
        final CommandLine command = new CommandLine(new SokarCli());
        command.setErr(new PrintWriter(err));

        final int code = CliErrors.failures(pathsIn(dir)).handleExecutionException(
                new IllegalStateException("Something went wrong in there"), command, null);

        assertThat(code).isEqualTo(70);
        assertThat(err.toString())
                .contains("sokar: something went wrong in there")
                .as("the terminal gets the sentence, not the frames")
                .doesNotContain("at org.fuin");

        // Kept rather than offered behind a flag: the failure nobody can reproduce is exactly
        // the one worth having a trace for.
        final java.nio.file.Path log = pathsIn(dir).failureLog();
        assertThat(log).exists();
        assertThat(java.nio.file.Files.readString(log))
                .contains("IllegalStateException")
                .contains("at org.fuin.sokar.app.CliErrorsTest");
        assertThat(err.toString()).contains(log.toString());
    }

    @Test
    void namesTheFailureWhenItCarriesNoMessage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {

        // Some exceptions carry only a type. Naming it beats an empty line that says something
        // went wrong without saying anything.
        final StringWriter err = new StringWriter();
        final CommandLine command = new CommandLine(new SokarCli());
        command.setErr(new PrintWriter(err));

        CliErrors.failures(pathsIn(dir)).handleExecutionException(
                new java.util.NoSuchElementException(), command, null);

        assertThat(err.toString()).contains("sokar: NoSuchElementException");
    }

    @Test
    void keepsArgumentsOutOfTheLog(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {

        // An argument can carry a credential - '--upstream https://user:token@host' is the
        // obvious one - and a log is exactly where that must not end up. The command's name says
        // which command failed, which is what a trace beside it needs.
        final CommandLine command = SokarCli.commandLine(SokarContext.real());
        command.setErr(new PrintWriter(new StringWriter()));
        final CommandLine.ParseResult parsed =
                command.parseArgs("gate", "pending", "--project", "uc",
                        "--upstream", "https://u:s3cret@example.com");

        CliErrors.failures(pathsIn(dir)).handleExecutionException(
                new IllegalStateException("it broke"), command, parsed);

        final String written = java.nio.file.Files.readString(pathsIn(dir).failureLog());
        assertThat(written).contains("gate pending").doesNotContain("s3cret");
    }

    @Test
    void keepsTheLogToItsOwner(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {

        // A message can quote anything the command was working with.
        final CommandLine command = new CommandLine(new SokarCli());
        command.setErr(new PrintWriter(new StringWriter()));
        CliErrors.failures(pathsIn(dir)).handleExecutionException(
                new IllegalStateException("it broke"), command, null);

        assertThat(java.nio.file.Files.getPosixFilePermissions(pathsIn(dir).failureLog()))
                .containsExactlyInAnyOrder(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
    }
}
