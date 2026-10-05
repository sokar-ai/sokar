package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.ArgSpec;

/**
 * An option picocli may leave unset is declared {@code @Nullable}.
 * <p>
 * NullAway is told that picocli initializes its fields, because it does - but only the ones it is
 * given. An optional option with no default is still {@code null} when {@code call()} runs, and a
 * field that does not say so is one NullAway trusts and nothing checks. This is what keeps that
 * trust honest, for every command, including the next one somebody adds.
 */
class OptionNullnessTest {

    @Test
    void everyOptionPicocliMayLeaveUnsetSaysItMayBeNull() {
        final List<String> undeclared = new ArrayList<>();
        walk(SokarCli.commandLine(SokarContext.real()), undeclared);

        assertThat(undeclared.stream().distinct().toList())
                .as("options and parameters that can be null and do not say so").isEmpty();
    }

    @Test
    void findsAnUnsetOptionThatDoesNotSaySo() {
        final List<String> undeclared = new ArrayList<>();
        walk(new CommandLine(new Fixture()), undeclared);

        assertThat(undeclared).containsExactly("fixture --unsaid (Fixture.unsaid)");
    }

    @CommandLine.Command(name = "fixture")
    static final class Fixture implements Runnable {

        @CommandLine.Option(names = "--unsaid")
        private String unsaid;

        @CommandLine.Option(names = "--said")
        private @Nullable String said;

        @CommandLine.Option(names = "--needed", required = true)
        private String needed;

        @CommandLine.Option(names = "--defaulted")
        private String defaulted = "x";

        @CommandLine.Option(names = "--flag")
        private boolean flag;

        @CommandLine.Parameters(index = "0")
        private String first;

        @Override
        public void run() {
            // Only its model is read.
        }
    }

    private static void walk(CommandLine command, List<String> undeclared) {
        final List<ArgSpec> arguments = new ArrayList<>(command.getCommandSpec().options());
        arguments.addAll(command.getCommandSpec().positionalParameters());
        for (final ArgSpec argument : arguments) {
            if (mayBeNull(argument) && argument.userObject() instanceof Field field
                    && !field.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                undeclared.add(command.getCommandSpec().qualifiedName() + " " + name(argument) + " ("
                        + field.getDeclaringClass().getSimpleName() + "." + field.getName() + ")");
            }
        }
        command.getSubcommands().values().forEach(sub -> walk(sub, undeclared));
    }

    private static boolean mayBeNull(ArgSpec argument) {
        return !argument.required() && argument.defaultValue() == null && argument.initialValue() == null
                && !argument.type().isPrimitive();
    }

    private static String name(ArgSpec argument) {
        return argument instanceof CommandLine.Model.OptionSpec option ? option.longestName() : argument.paramLabel();
    }

}
