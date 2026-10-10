package org.fuin.sokar.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.process.Command;

/**
 * The environment the message filter and every transport start from: chosen, never the caller's whole one.
 * <p>
 * <strong>What the caller had does not decide what they do</strong> (decided on 2026-09-30).
 * Inherited, a {@code SOKAR_MSGSLUICE_*} in the shell that started Sokar switched the filter's checks off, and a
 * {@code SOKAR_MATRIX_TLS_VERIFY=off} switched a transport's certificate checks off; an outer agent's session
 * variables reached them too. They get what a process needs to find its tools, its home and its account's user
 * services - and exactly what Sokar hands them on top.
 */
public final class HelperEnvironment {

    /** What passes from the caller: the search path, who and where the account is, its language, its user bus. */
    static final List<String> PASSED = List.of("PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LANGUAGE",
            "LC_ALL", "LC_CTYPE", "LC_MESSAGES", "TZ", "TMPDIR", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_STATE_HOME",
            "XDG_CACHE_HOME", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS");

    private HelperEnvironment() {
    }

    /**
     * Returns a helper's command, starting from the chosen environment.
     *
     * @param command What runs the helper, with what Sokar hands it.
     * @return The same, with nothing else of the caller's.
     */
    public static Command chosen(Command command) {
        return command.withChosenEnvironment(base(System.getenv()));
    }

    /**
     * Returns what passes from an environment.
     *
     * @param caller The caller's environment.
     * @return Only the variables that pass.
     */
    static Map<String, String> base(Map<String, String> caller) {
        final Map<String, String> base = new LinkedHashMap<>();
        for (final String name : PASSED) {
            final String value = caller.get(name);
            if (value != null) {
                base.put(name, value);
            }
        }
        return base;
    }
}
