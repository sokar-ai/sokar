package org.fuin.sokar.shield;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.sokar.core.project.SecurityClass;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DnsPolicy}.
 */
class DnsPolicyTest {

    @Test
    void answersNothingByDefault() {

        // Same shape as the packet filter: deny by default, widen deliberately. It also closes
        // DNS exfiltration, where an agent encodes data into names nobody asked to be resolvable.
        for (final SecurityClass securityClass : SecurityClass.values()) {
            assertThat(new DnsPolicy(securityClass).render())
                    .as("security class %s", securityClass)
                    .contains("address=/#/");
        }
    }

    @Test
    void resolvesOnlyTheDomainsAProjectAllows() {

        final String rendered = new DnsPolicy(SecurityClass.GUARDED)
                .upstream("8.8.8.8")
                .allow("github.com")
                .allow("pypi.org")
                .render();

        assertThat(rendered)
                .contains("server=/github.com/8.8.8.8")
                .contains("server=/pypi.org/8.8.8.8")
                .contains("address=/#/");
        assertThat(rendered).doesNotContain("server=/gitlab.com/");
    }

    @Test
    void anOfflineProjectResolvesNothingEvenWithDomainsListed() {

        // The class decides, not the list. Adding a domain to an offline project must not open it.
        final String rendered = new DnsPolicy(SecurityClass.OFFLINE)
                .upstream("8.8.8.8")
                .allow("github.com")
                .render();

        assertThat(rendered).doesNotContain("server=/github.com/");
        assertThat(rendered).contains("address=/#/");
    }

    @Test
    void resolvesNothingWithoutAnUpstream() {

        // A forwarder with nowhere to forward to must fail closed, not fall back to the host's
        // resolvers - which is exactly what dnsmasq would do without 'no-resolv'.
        final String rendered = new DnsPolicy(SecurityClass.ONLINE).allow("github.com").render();

        assertThat(rendered).doesNotContain("server=/github.com/");
        assertThat(rendered).contains("no-resolv");
    }

    @Test
    void doesNotCacheAnswers() {

        // A cached answer keeps a name resolving after the operator revoked it, for as long as the
        // TTL says.
        assertThat(new DnsPolicy(SecurityClass.GUARDED).render()).contains("cache-size=0");
    }

    @Test
    void logsEveryQuery() {

        assertThat(new DnsPolicy(SecurityClass.GUARDED).render()).contains("log-queries");
    }

    @Test
    void survivesRunningInsideAUserNamespace() {

        // dnsmasq writes /var/run/dnsmasq.pid and drops privileges to 'nobody' by default. Under
        // 'podman unshare' the process is root only within the namespace: /var/run belongs to the
        // real root and there is no mapped 'nobody'. Both defaults fail, and dnsmasq exits before
        // answering a single query.
        final String rendered = new DnsPolicy(SecurityClass.GUARDED).render();

        assertThat(rendered).contains("pid-file=");
        assertThat(rendered).contains("user=root");
        assertThat(rendered).contains("log-facility=-");
    }

    @Test
    void listensOnlyOnLoopbackInsideTheContainer() {

        assertThat(new DnsPolicy(SecurityClass.GUARDED).render())
                .contains("listen-address=127.0.0.1")
                .contains("bind-interfaces");
    }

    @Test
    void runsInsideTheContainersNamespace() {

        assertThat(String.join(" ", DnsPolicy.command(4711L, "/tmp/dns.conf")))
                .isEqualTo("podman unshare nsenter --target 4711 --net"
                        + " dnsmasq --keep-in-foreground --conf-file=/tmp/dns.conf");
    }
}
