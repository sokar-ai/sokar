package org.fuin.sokar.shield;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.sokar.core.project.SecurityClass;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DnsPolicy}.
 */
class DnsPolicyTest {

    /** Where the per-domain lines go, as the launcher writes it beside the configuration. */
    private static final String SERVERS = "/run/user/1000/sokar/box/" + DnsPolicy.SERVERS_FILE;

    @Test
    void answersNothingByDefault() {

        // Same shape as the packet filter: deny by default, widen deliberately. It also closes
        // DNS exfiltration, where an agent encodes data into names nobody asked to be resolvable.
        for (final SecurityClass securityClass : SecurityClass.values()) {
            assertThat(new DnsPolicy(securityClass).render(SERVERS))
                    .as("security class %s", securityClass)
                    .contains("address=/#/");
        }
    }

    @Test
    void resolvesOnlyTheDomainsAProjectAllows() {

        final DnsPolicy policy = new DnsPolicy(SecurityClass.GUARDED)
                .upstream("8.8.8.8")
                .allow("github.com")
                .allow("pypi.org");

        // The names live in the servers file, which is the only part dnsmasq re-reads.
        assertThat(policy.renderServers())
                .contains("server=/github.com/8.8.8.8")
                .contains("server=/pypi.org/8.8.8.8")
                .doesNotContain("server=/gitlab.com/");
        assertThat(policy.render(SERVERS))
                .contains("address=/#/")
                .contains("servers-file=" + SERVERS);
    }

    @Test
    void anOfflineProjectResolvesNothingEvenWithDomainsListed() {

        // The class decides, not the list. Adding a domain to an offline project must not open it.
        final DnsPolicy policy = new DnsPolicy(SecurityClass.OFFLINE)
                .upstream("8.8.8.8")
                .allow("github.com");

        assertThat(policy.renderServers()).doesNotContain("server=/github.com/");
        assertThat(policy.render(SERVERS)).contains("address=/#/");
    }

    @Test
    void resolvesNothingWithoutAnUpstream() {

        // A forwarder with nowhere to forward to must fail closed, not fall back to the host's
        // resolvers - which is exactly what dnsmasq would do without 'no-resolv'.
        final DnsPolicy policy = new DnsPolicy(SecurityClass.ONLINE).allow("github.com");

        assertThat(policy.renderServers()).doesNotContain("server=/github.com/");
        assertThat(policy.render(SERVERS)).contains("no-resolv");
    }

    @Test
    void doesNotCacheAnswers() {

        // A cached answer keeps a name resolving after the operator revoked it, for as long as the
        // TTL says.
        assertThat(new DnsPolicy(SecurityClass.GUARDED).render(SERVERS)).contains("cache-size=0");
    }

    @Test
    void logsEveryQuery() {

        assertThat(new DnsPolicy(SecurityClass.GUARDED).render(SERVERS)).contains("log-queries");
    }

    @Test
    void survivesRunningInsideAUserNamespace() {

        // dnsmasq writes /var/run/dnsmasq.pid and drops privileges to 'nobody' by default. Under
        // 'podman unshare' the process is root only within the namespace: /var/run belongs to the
        // real root and there is no mapped 'nobody'. Both defaults fail, and dnsmasq exits before
        // answering a single query.
        final String rendered = new DnsPolicy(SecurityClass.GUARDED).render(SERVERS);

        assertThat(rendered).contains("pid-file=");
        assertThat(rendered).contains("user=root");
        assertThat(rendered).contains("log-facility=-");
    }

    @Test
    void listensOnlyOnLoopbackInsideTheContainer() {

        assertThat(new DnsPolicy(SecurityClass.GUARDED).render(SERVERS))
                .contains("listen-address=127.0.0.1")
                .contains("bind-interfaces");
    }

    @Test
    void keepsTheNamesWhereDnsmasqWillReadThemAgain(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path dir) throws java.io.IOException {

        // The whole point of the split: a servers-file is re-read on SIGHUP and the configuration
        // is not, which is what lets a running task be widened by name. Measured against real
        // dnsmasq: NXDOMAIN before a line was appended here and signaled, real addresses after,
        // same process.
        final java.nio.file.Path config = dir.resolve("dnsmasq.conf");
        new DnsPolicy(SecurityClass.GUARDED).upstream("8.8.8.8").allow("github.com")
                .writeTo(config);

        final java.nio.file.Path servers = dir.resolve(DnsPolicy.SERVERS_FILE);
        assertThat(java.nio.file.Files.readString(servers)).contains("server=/github.com/8.8.8.8");
        assertThat(java.nio.file.Files.readString(config))
                .contains("servers-file=" + servers)
                .doesNotContain("server=/github.com/");
    }

    @Test
    void writesBothFilesOrDnsmasqWillNotStart(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path dir) throws java.io.IOException {

        // A configuration pointing at a servers file that is not there makes dnsmasq refuse to
        // start, which reaches an operator as a container with no resolver at all.
        new DnsPolicy(SecurityClass.GUARDED).writeTo(dir.resolve("dnsmasq.conf"));

        assertThat(java.nio.file.Files.exists(dir.resolve(DnsPolicy.SERVERS_FILE))).isTrue();
    }

    @Test
    void runsInsideTheContainersNamespace() {

        assertThat(String.join(" ", DnsPolicy.command(4711L, "/tmp/dns.conf")))
                .isEqualTo("podman unshare nsenter --target 4711 --net"
                        + " dnsmasq --keep-in-foreground --conf-file=/tmp/dns.conf");
    }

    @Test
    void allowsBothAddressFamiliesForADeclaredDomain() {

        // Found in the wild: a declared name answered AAAA, only the v4 set was populated, and
        // the operator got a clearance prompt for a bare IPv6 address they had no way to place.
        // The ruleset has always had an allowed_v6 set; nothing filled it.
        final String rendered = new DnsPolicy(SecurityClass.GUARDED)
                .upstream("1.1.1.1").autoAllow("example.test").render(SERVERS);

        assertThat(rendered).contains("inet#sokar#allowed_v4");
        assertThat(rendered).contains("inet#sokar#allowed_v6");
    }

    @Test
    void refusesANameUnderAnAllowedParentInTheConfigurationNotTheServersFile() {
        final DnsPolicy policy = new DnsPolicy(SecurityClass.GUARDED)
                .upstream("8.8.8.8").autoAllow("claude.ai").refuse("downloads.claude.ai");

        // In the configuration, which a widening never rewrites: a refusal a running task cannot lose.
        assertThat(policy.render(SERVERS)).contains("address=/downloads.claude.ai/");
        assertThat(policy.renderServers()).contains("server=/claude.ai/8.8.8.8")
                .doesNotContain("downloads.claude.ai");
    }

    @Test
    void anOfflineProjectNeedsNoRefusalWrittenOut() {
        // Everything is NXDOMAIN there already; a line per refusal would say nothing.
        assertThat(new DnsPolicy(SecurityClass.OFFLINE).refuse("telemetry.example").render(SERVERS))
                .doesNotContain("address=/telemetry.example/");
    }
}
