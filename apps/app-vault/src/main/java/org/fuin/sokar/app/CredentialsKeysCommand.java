package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Lists the ssh keys this account already has.
 * <p>
 * So that "use the key that is already here" is a choice from a list rather than a path typed from
 * memory. Two wrong paths were typed in one afternoon - one from another machine, one the public
 * half - and both were recorded as perfectly good records pointing at nothing.
 */
@Command(name = "keys",
        mixinStandardHelpOptions = true,
        description = "Lists the ssh keys this account has, without reading any of them.")
public class CredentialsKeysCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final List<SshKeys.Key> keys =
                new SshKeys(context.paths().xdg().home()).all();
        if (keys.isEmpty()) {
            out.println("No ssh keys in ~/.ssh. 'ssh-keygen -t ed25519' makes one, and its public"
                    + " half is what a forge has to be told about.");
            out.flush();
            return 0;
        }
        final int width = keys.stream().mapToInt(key -> key.path().length()).max().orElse(0);
        for (final SshKeys.Key key : keys) {
            out.printf("%-" + width + "s  %-12s  %s%n", key.path(),
                    key.type().isEmpty() ? "(encrypted)" : key.type(),
                    key.fingerprint().isEmpty() ? "" : key.fingerprint());
            if (!key.comment().isEmpty()) {
                out.printf("%-" + width + "s  %s%n", "", key.comment());
            }
            final String obstacle = key.obstacle();
            if (obstacle != null) {
                // Said under the key it belongs to, because this is the list somebody picks from
                // and the thing they need to know is why one of them will not do.
                out.printf("%-" + width + "s  %s%n", "", obstacle);
            }
        }
        out.flush();
        return 0;
    }
}
