package org.fuin.sokar.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TokenBroker}.
 */
class TokenBrokerTest {

    private static final Map<String, String> REAL =
            Map.of("github", "ghp_the_real_one", "gitlab", "glpat_the_real_one");

    private final TokenBroker broker = new TokenBroker(() -> REAL);

    /** Minting uses the real clock, so the comparison point has to come from it too. */
    private final Instant now = Instant.now();

    @Test
    void exchangesAMintedTokenForTheRealCredential() {

        final PhantomToken token = broker.mint("github", "task-1", Duration.ofHours(1));

        assertThat(broker.exchange(token.value(), now)).contains("ghp_the_real_one");
    }

    @Test
    void thePhantomLooksNothingLikeTheRealCredential() {

        final PhantomToken token = broker.mint("github", "task-1", Duration.ofHours(1));

        // A leaked token should be recognisable as Sokar's, so whoever finds it knows it is not a
        // provider credential to be panicked about.
        assertThat(token.value()).startsWith("sokar_pt_").isNotEqualTo("ghp_the_real_one");
    }

    @Test
    void doesNotPutTheTokenInItsOwnToString() {

        final PhantomToken token = broker.mint("github", "task-1", Duration.ofHours(1));

        // Tokens reach logs and exception messages by accident.
        assertThat(token.toString()).doesNotContain(token.value().substring(20));
    }

    @Test
    void refusesAnUnknownToken() {

        assertThat(broker.exchange("sokar_pt_made_up", now)).isEmpty();
    }

    @Test
    void refusesAnExpiredToken() {

        final PhantomToken token = broker.mint("github", "task-1", Duration.ofMinutes(5));

        assertThat(broker.exchange(token.value(), now.plus(Duration.ofHours(1)))).isEmpty();
    }

    @Test
    void refusesARevokedToken() {

        final PhantomToken token = broker.mint("github", "task-1", Duration.ofHours(1));

        assertThat(broker.revoke(token.value())).isTrue();
        assertThat(broker.exchange(token.value(), now)).isEmpty();
    }

    @Test
    void revokesEverythingATaskWasGivenWhenItEnds() {

        final PhantomToken first = broker.mint("github", "task-1", Duration.ofHours(1));
        final PhantomToken second = broker.mint("gitlab", "task-1", Duration.ofHours(1));
        final PhantomToken other = broker.mint("github", "task-2", Duration.ofHours(1));

        assertThat(broker.revokeAllFor("task-1")).isEqualTo(2);
        assertThat(broker.exchange(first.value(), now)).isEmpty();
        assertThat(broker.exchange(second.value(), now)).isEmpty();
        assertThat(broker.exchange(other.value(), now)).isPresent();
    }

    @Test
    void refusesToMintForAScopeItCannotHonour() {

        // Otherwise the failure surfaces inside the agent as an authentication error against the
        // provider, which is the hardest possible place to work out what went wrong.
        assertThatThrownBy(() -> broker.mint("bitbucket", "task-1", Duration.ofHours(1)))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("no credential for scope 'bitbucket'");
    }

    @Test
    void aTokenForOneProviderDoesNotOpenAnother() {

        final PhantomToken token = broker.mint("github", "task-1", Duration.ofHours(1));

        assertThat(broker.exchange(token.value(), now)).hasValue("ghp_the_real_one");
        assertThat(broker.exchange(token.value(), now)).isNotEqualTo(
                java.util.Optional.of("glpat_the_real_one"));
    }

    @Test
    void expiredTokensAreCleanedUp() {

        broker.mint("github", "task-1", Duration.ofMinutes(1));
        broker.mint("github", "task-2", Duration.ofHours(2));

        assertThat(broker.expire(Instant.now().plus(Duration.ofMinutes(30)))).isEqualTo(1);
        assertThat(broker.outstanding()).isEqualTo(1);
    }

    @Test
    void readsTheCredentialFreshOnEveryExchange() {

        // Caching would hold the real credential in memory for the life of the task instead of the
        // length of one request, for no benefit.
        final java.util.concurrent.atomic.AtomicInteger reads =
                new java.util.concurrent.atomic.AtomicInteger();
        final TokenBroker counting = new TokenBroker(() -> {
            reads.incrementAndGet();
            return REAL;
        });

        final PhantomToken token = counting.mint("github", "task-1", Duration.ofHours(1));
        counting.exchange(token.value(), now);
        counting.exchange(token.value(), now);

        assertThat(reads.get()).isGreaterThanOrEqualTo(3);
    }
}
