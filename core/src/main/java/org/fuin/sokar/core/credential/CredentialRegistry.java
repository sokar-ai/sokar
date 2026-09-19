package org.fuin.sokar.core.credential;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * What credentials this machine has, and which one a destination gets.
 * <p>
 * <strong>Readable with the vault shut</strong>, which is the whole reason it is a file of its own.
 * A machine that keeps its only record of a credential inside the encrypted thing cannot tell
 * <em>"a credential for this host is configured, unlock the vault"</em> from <em>"nothing is
 * configured, store one"</em> - and those two send a person to opposite places. Nothing secret is
 * in here: names, kinds, destinations, usernames.
 * <p>
 * <strong>The longest match wins.</strong> A URL is normalised - {@code git@host:path} is
 * {@code ssh://host/path} - and the record whose {@code match} is the longest prefix of it is the
 * one used. That is how one credential covers a forge and another covers one group on it, without
 * an ordering rule nobody can remember.
 * <p>
 * <strong>A missing file is not an error.</strong> A machine that has never needed one has none,
 * and callers fall back to what they did before.
 */
public final class CredentialRegistry {

    private final List<Credential> credentials;

    /**
     * Constructor with what was read.
     *
     * @param credentials The records.
     */
    public CredentialRegistry(final List<Credential> credentials) {
        this.credentials = List.copyOf(credentials);
    }

    /**
     * Reads the registry, or an empty one.
     *
     * @param file Where it lives, usually {@code ~/.config/sokar/credentials.yml}.
     * @return What it holds.
     * @throws CredentialException If the file is there and cannot be read as one.
     */
    public static CredentialRegistry read(final Path file) {
        if (!Files.isRegularFile(file)) {
            return new CredentialRegistry(List.of());
        }
        final Object loaded;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (final IOException ex) {
            throw new CredentialException("cannot read " + file + ": " + ex.getMessage());
        } catch (final RuntimeException ex) {
            throw new CredentialException(file + " is not readable as YAML: " + ex.getMessage());
        }
        if (loaded == null) {
            return new CredentialRegistry(List.of());
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new CredentialException(file + " should hold a 'credentials:' list");
        }
        final Object list = root.get("credentials");
        if (list == null) {
            return new CredentialRegistry(List.of());
        }
        if (!(list instanceof List<?> entries)) {
            throw new CredentialException("'credentials:' in " + file + " should be a list");
        }
        final List<Credential> read = new ArrayList<>();
        for (final Object entry : entries) {
            if (!(entry instanceof Map<?, ?> fields)) {
                throw new CredentialException("every entry in " + file + " should be a mapping");
            }
            read.add(one(file, fields));
        }
        return new CredentialRegistry(read);
    }

    private static Credential one(final Path file, final Map<?, ?> fields) {
        final String match = text(fields, "match");
        if (match == null || match.isBlank()) {
            throw new CredentialException("a credential in " + file + " names no 'match'");
        }
        final Credential.Source source = source(file, text(fields, "source"));
        final String id = text(fields, source == Credential.Source.VAULT ? "vault"
                : source == Credential.Source.FILE ? "file"
                : source == Credential.Source.ENVIRONMENT ? "env" : "vault");
        if (id == null && source != Credential.Source.AGENT) {
            throw new CredentialException("the credential for '" + match + "' in " + file
                    + " says source '" + source.name().toLowerCase(Locale.ROOT)
                    + "' and does not say where");
        }
        return new Credential(id == null ? "" : id, kind(file, match, text(fields, "kind")),
                normalise(match), text(fields, "user"),
                text(fields, "purpose") == null ? Credential.ANY : text(fields, "purpose"),
                source, text(fields, "expires"));
    }

    private static Credential.Kind kind(final Path file, final String match,
            final @Nullable String said) {
        if (said == null) {
            throw new CredentialException("the credential for '" + match + "' in " + file
                    + " does not say its 'kind': ssh-key, token, basic or oauth");
        }
        return switch (said.strip().toLowerCase(Locale.ROOT)) {
            case "ssh-key", "ssh", "key" -> Credential.Kind.SSH_KEY;
            case "token", "pat" -> Credential.Kind.TOKEN;
            case "basic", "password" -> Credential.Kind.BASIC;
            case "oauth" -> Credential.Kind.OAUTH;
            default -> throw new CredentialException("'" + said + "' in " + file
                    + " is not a kind of credential: ssh-key, token, basic or oauth");
        };
    }

    private static Credential.Source source(final Path file, final @Nullable String said) {
        if (said == null) {
            return Credential.Source.VAULT;
        }
        return switch (said.strip().toLowerCase(Locale.ROOT)) {
            case "vault" -> Credential.Source.VAULT;
            case "file" -> Credential.Source.FILE;
            case "env", "environment" -> Credential.Source.ENVIRONMENT;
            case "agent", "ssh-agent" -> Credential.Source.AGENT;
            default -> throw new CredentialException("'" + said + "' in " + file
                    + " is not a source: vault, file, env or agent");
        };
    }

    private static @Nullable String text(final Map<?, ?> fields, final String key) {
        final Object value = fields.get(key);
        return value == null ? null : String.valueOf(value).strip();
    }

    /**
     * Returns the credential for a destination, or {@code null}.
     *
     * @param purpose What the caller is doing, such as {@code git}.
     * @param url Where it is connecting.
     * @return The longest match that serves this purpose.
     */
    public @Nullable Credential forUrl(final String purpose, final @Nullable String url) {
        if (url == null) {
            return null;
        }
        final String normalised = normalise(url);
        Credential best = null;
        for (final Credential candidate : credentials) {
            if (!candidate.serves(purpose) || !candidate.covers(normalised)) {
                continue;
            }
            if (best == null || candidate.match().length() > best.match().length()) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Returns every record, in the order the file gives them.
     *
     * @return The credentials.
     */
    public List<Credential> all() {
        return credentials;
    }

    /**
     * Writes a URL the way {@code match} is compared against.
     * <p>
     * {@code git@github.com:acme/x.git} and {@code ssh://git@github.com/acme/x.git} are the same
     * destination written two ways, and a person should not have to write a record for each.
     *
     * @param url Any git-style URL.
     * @return The normalised form, lower-cased in its host.
     */
    public static String normalise(final String url) {
        final String trimmed = url.strip();
        final int scheme = trimmed.indexOf("://");
        if (scheme >= 0) {
            final String protocol = trimmed.substring(0, scheme).toLowerCase(Locale.ROOT);
            String rest = trimmed.substring(scheme + 3);
            final int at = rest.indexOf('@');
            final int slash = rest.indexOf('/');
            if (at >= 0 && (slash < 0 || at < slash)) {
                // The user in a URL is not part of the destination: one record should cover
                // 'https://me@forge' and 'https://forge'.
                rest = rest.substring(at + 1);
            }
            return protocol + "://" + rest;
        }
        if (trimmed.matches("^[^/@]+@[^/:]+:.*")) {
            return "ssh://" + trimmed.substring(trimmed.indexOf('@') + 1).replaceFirst(":", "/");
        }
        return trimmed;
    }
}
