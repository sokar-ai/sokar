package org.fuin.sokar.clearance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.fuin.sokar.wire.varlink.VarlinkClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ClearanceService}: what a report carries to every subscriber.
 */
class ClearanceServiceTest {

    @Test
    void aReportReachesSubscribersAsWhatWasBlockedAndNothingItsSenderAdded(@TempDir Path dir) throws Exception {

        // Forwarded as it came, a report carrying 'verdict' and 'source' read to an interface like an answer.
        final ClearanceHub hub = new ClearanceHub("uc", "shell", request -> Verdict.DENY, (address, name) -> { });
        final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        try (ClearanceService service = new ClearanceService(dir.resolve("c.sock"), hub)) {
            service.start();
            final Thread listener = Thread.ofVirtual().start(() -> {
                try (VarlinkClient client = new VarlinkClient(dir.resolve("c.sock"))) {
                    client.callMore(ClearanceService.INTERFACE + ".Subscribe", Map.of(),
                            event -> events.add(event) && events.size() < 2);
                } catch (Exception ex) {
                    // The test reads what arrived.
                }
            });
            for (int i = 0; i < 50 && service.subscriberCount() == 0; i++) {
                Thread.sleep(20);
            }
            try (VarlinkClient reporter = new VarlinkClient(dir.resolve("c.sock"))) {
                reporter.call(ClearanceService.INTERFACE + ".Report", Map.of("destination", "1.1.1.1", "port", 443,
                        "protocol", "tcp", "verdict", "allow", "source", "client"));
            }
            listener.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertThat(events).isNotEmpty();
        assertThat(events.getFirst()).containsEntry("destination", "1.1.1.1").doesNotContainKeys("verdict", "source");
    }
}
