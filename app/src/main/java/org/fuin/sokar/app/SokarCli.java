package org.fuin.sokar.app;

import org.fuin.sokar.core.hardening.ProcessHardening;

/**
 * Entry point of the {@code sokar} binary.
 * <p>
 * Placeholder for the picocli command tree. For now it reports the process-hardening state, which
 * exercises a real FFM downcall and so proves that the committed reachability metadata is complete
 * for this binary.
 */
public final class SokarCli {

    private SokarCli() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void main(String[] args) {
        System.out.println("sokar (skeleton)");
        System.out.println("  dumpable            = " + ProcessHardening.dumpable());
        System.out.println("  noNewPrivileges     = " + ProcessHardening.noNewPrivileges());
        System.out.println("  hardening covers    = "
                + (ProcessHardening.appliesToWholeProcess() ? "the whole process" : "this thread only"));
    }
}
