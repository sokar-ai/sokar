package org.fuin.sokar.clearance;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

/**
 * Asks through a desktop notification with Allow and Deny buttons.
 * <p>
 * Uses the session bus, so it only works where the operator actually is. A container's own
 * processes cannot reach it: stuck in a rootless user namespace, they fail the bus's
 * {@code SO_PEERCRED} check. That is the reason the prompt lives on this side of the boundary and
 * the reader only reports events.
 */
public class DesktopPrompt implements ClearancePrompt, AutoCloseable {

    /** Action key sent back when the operator presses Allow. */
    public static final String ALLOW = "allow";

    /** Action key sent back when the operator presses Deny. */
    public static final String DENY = "deny";

    /** How long the expired notice stays up: -1 leaves it to the notification server. */
    private static final int EXPIRED_TIMEOUT = -1;

    /**
     * The part of {@code org.freedesktop.Notifications} Sokar uses.
     */
    @DBusInterfaceName("org.freedesktop.Notifications")
    public interface Notifications extends DBusInterface {

        /**
         * Shows a notification.
         *
         * @param appName Application name.
         * @param replacesId Notification to replace, or zero.
         * @param appIcon Icon name.
         * @param summary One-line summary.
         * @param body Body text.
         * @param actions Alternating action key and label.
         * @param hints Extra hints.
         * @param timeout Milliseconds, or zero to never expire.
         * @return Notification id.
         */
        UInt32 Notify(String appName, UInt32 replacesId, String appIcon, String summary,
                String body, List<String> actions, Map<String, Variant<?>> hints, int timeout);

        /**
         * Closes a notification.
         *
         * @param id Notification id.
         */
        void CloseNotification(UInt32 id);

        /**
         * Sent when the operator presses one of the buttons.
         * <p>
         * Must be nested inside the interface: dbus-java derives the object path from the
         * enclosing type, and a top-level signal class is rejected at runtime.
         */
        class ActionInvoked extends DBusSignal {

            /** Notification the action belongs to. */
            public final UInt32 id;

            /** Key of the pressed button. */
            public final String actionKey;

            /**
             * Constructor.
             *
             * @param path Object path.
             * @param id Notification id.
             * @param actionKey Key of the pressed button.
             * @throws Exception If the signal cannot be built.
             */
            public ActionInvoked(String path, UInt32 id, String actionKey) throws Exception {
                super(path, id, actionKey);
                this.id = id;
                this.actionKey = actionKey;
            }
        }

        /**
         * Sent when a notification disappears without an action.
         */
        class NotificationClosed extends DBusSignal {

            /** Notification that closed. */
            public final UInt32 id;

            /** Why it closed. */
            public final UInt32 reason;

            /**
             * Constructor.
             *
             * @param path Object path.
             * @param id Notification id.
             * @param reason Close reason.
             * @throws Exception If the signal cannot be built.
             */
            public NotificationClosed(String path, UInt32 id, UInt32 reason) throws Exception {
                super(path, id, reason);
                this.id = id;
                this.reason = reason;
            }
        }
    }

    private final DBusConnection connection;

    private final Notifications notifications;

    private final Duration timeout;

    /**
     * Connects to the session bus.
     *
     * @param timeout How long to wait for an answer.
     * @throws ClearanceException If the session bus cannot be reached.
     */
    public DesktopPrompt(Duration timeout) {
        this.timeout = timeout;
        try {
            connection = DBusConnectionBuilder.forSessionBus().build();
            notifications = connection.getRemoteObject("org.freedesktop.Notifications",
                    "/org/freedesktop/Notifications", Notifications.class);
        } catch (Exception ex) {
            throw new ClearanceException("Cannot reach the desktop notification service: "
                    + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : ": " + ex.getMessage()), ex);
        }
    }

    @Override
    public Verdict ask(ClearanceRequest request) {

        final CountDownLatch answered = new CountDownLatch(1);
        final AtomicReference<Verdict> verdict = new AtomicReference<>(Verdict.TIMEOUT);
        final AtomicReference<UInt32> shown = new AtomicReference<>();

        // Removed again when the question is done: registered per question and never removed, every handler stayed,
        // kept its question, and every signal walked them all.
        try (AutoCloseable handler = connection.addSigHandler(Notifications.ActionInvoked.class, signal -> {
                if (shown.get() != null && shown.get().equals(signal.id)) {
                    verdict.set(ALLOW.equals(signal.actionKey) ? Verdict.ALLOW : Verdict.DENY);
                    answered.countDown();
                }
            })) {

            final UInt32 id = notifications.Notify("Sokar", new UInt32(0), "network-error",
                    request.summary(), request.body(),
                    List.of(ALLOW, "Allow", DENY, "Deny"),
                    Map.of("urgency", new Variant<>((byte) 2)),
                    (int) timeout.toMillis());
            shown.set(id);

            if (!answered.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                expire(id, request);
            }
            return java.util.Objects.requireNonNullElse(verdict.get(), Verdict.TIMEOUT);

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Verdict.TIMEOUT;
        } catch (Exception ex) {
            throw new ClearanceException("Cannot ask about " + request.destination(), ex);
        }
    }

    /**
     * Replaces a question nobody answered with what its silence did.
     * <p>
     * Replaced rather than closed. Leaving the question on screen would invite an answer nothing
     * is listening for, but simply taking it away is worse: the destination stays blocked and will
     * not be asked about again, so an operator who was away comes back to a machine that looks as
     * though it was never asked. The buttons go with it, and the urgency drops - critical
     * notifications never expire on their own, and this one has nothing left to decide.
     *
     * @param id The notification to replace.
     * @param request What was asked.
     */
    private void expire(UInt32 id, ClearanceRequest request) {
        notifications.Notify("Sokar", id, "network-error", request.expiredSummary(),
                request.expiredBody(), List.of(), Map.of("urgency", new Variant<>((byte) 1)),
                EXPIRED_TIMEOUT);
    }

    @Override
    public void close() {
        connection.disconnect();
    }
}
