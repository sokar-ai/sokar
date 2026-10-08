package org.fuin.sokar.core.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.fuin.sokar.core.config.XdgPaths;

/**
 * The certificate authorities Sokar trusts: the ones it was built with, and those in {@value #FILE} in the account's
 * configuration directory.
 * <p>
 * An organisation that inspects TLS on its way out signs with an authority of its own, and a provider on a machine of
 * one's own may carry a certificate no public authority signed. Both are trusted by putting the authority's PEM
 * certificate in the file. Without the file nothing changes.
 */
public final class TrustedCertificates {

    /** The file's name, in the account's configuration directory, {@code $XDG_CONFIG_HOME/sokar}. */
    public static final String FILE = "ca-certificates.pem";

    private TrustedCertificates() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns where the account's additional authorities are.
     *
     * @param xdg The account's directories.
     * @return The file, which need not exist.
     */
    public static Path file(final XdgPaths xdg) {
        return xdg.config().resolve(FILE);
    }

    /**
     * Returns an HTTP client builder that trusts what this class says.
     *
     * @param file The additional authorities, as {@link #file} names them.
     * @return A builder; the caller sets the rest.
     * @throws IllegalStateException When the file exists and cannot be read as certificates: an authority meant to be
     *         trusted and silently not would turn into a refusal nobody can explain.
     */
    public static HttpClient.Builder builder(final Path file) {
        return HttpClient.newBuilder().sslContext(context(file));
    }

    /**
     * Returns an HTTP client builder for this account as the environment names it.
     *
     * @return A builder.
     */
    public static HttpClient.Builder builder() {
        return builder(file(XdgPaths.current()));
    }

    /**
     * Returns the TLS context: the default one without the file, else one trusting the built-in authorities and the
     * file's.
     *
     * @param file The additional authorities.
     * @return The context.
     */
    public static SSLContext context(final Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return SSLContext.getDefault();
            }
            final Collection<? extends Certificate> added;
            try (InputStream in = Files.newInputStream(file)) {
                added = CertificateFactory.getInstance("X.509").generateCertificates(in);
            }
            if (added.isEmpty()) {
                throw new IllegalStateException(file + " holds no certificate; put the authority's PEM certificate"
                        + " there, or remove the file");
            }
            final KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int n = 0;
            for (final X509Certificate builtIn : builtIn().getAcceptedIssuers()) {
                store.setCertificateEntry("built-in-" + n++, builtIn);
            }
            for (final Certificate each : added) {
                store.setCertificateEntry("added-" + n++, each);
            }
            final TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            final SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("cannot read the certificates in " + file + ": " + ex.getMessage(), ex);
        }
    }

    private static X509TrustManager builtIn() throws GeneralSecurityException {
        final TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (final TrustManager each : factory.getTrustManagers()) {
            if (each instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new GeneralSecurityException("no X.509 trust manager is built in");
    }
}
