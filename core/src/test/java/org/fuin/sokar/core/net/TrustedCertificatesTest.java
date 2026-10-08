package org.fuin.sokar.core.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link TrustedCertificates}, against a TLS server whose certificate no public authority signed.
 */
class TrustedCertificatesTest {

    private static void keytool(final Path dir, final String... arguments) throws Exception {
        final java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        command.addAll(java.util.List.of(arguments));
        final Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        final String said = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(said).isZero();
    }

    @Test
    void anAuthorityInTheFileIsTrustedAndWithoutTheFileTheServerIsRefused(@TempDir final Path dir) throws Exception {

        // Measuring with a provider on the machine itself needed it over https; an organisation inspecting TLS
        // needs the same: an authority of its own.
        keytool(dir, "-genkeypair", "-alias", "local", "-keyalg", "EC", "-groupname", "secp256r1", "-dname",
                "CN=localhost", "-ext", "SAN=IP:127.0.0.1,DNS:localhost", "-validity", "2", "-keystore", "server.p12",
                "-storetype", "PKCS12", "-storepass", "changeit");
        keytool(dir, "-exportcert", "-rfc", "-alias", "local", "-keystore", "server.p12", "-storepass", "changeit",
                "-file", "ca.pem");
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(dir.resolve("server.p12"))) {
            keys.load(in, "changeit".toCharArray());
        }
        final KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "changeit".toCharArray());
        final SSLContext serverSide = SSLContext.getInstance("TLS");
        serverSide.init(managers.getKeyManagers(), null, null);
        final HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverSide));
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("ok".getBytes());
            exchange.close();
        });
        server.start();
        try {
            final URI uri = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/");
            final Path file = dir.resolve("config/sokar").resolve(TrustedCertificates.FILE);

            assertThatThrownBy(() -> TrustedCertificates.builder(file).build()
                    .send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()))
                    .as("no file: only the built-in authorities").isInstanceOf(javax.net.ssl.SSLHandshakeException.class);

            Files.createDirectories(file.getParent());
            Files.copy(dir.resolve("ca.pem"), file);
            assertThat(TrustedCertificates.builder(file).build()
                    .send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body())
                    .isEqualTo("ok");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theFileIsWhereTheDocumentationSaysInTheAccountsConfiguration() {

        // A proxy looked in ~/.config/sokar/sokar/ and trusted nothing more: the test above built its
        // own path and never asked this one.
        assertThat(TrustedCertificates.file(org.fuin.sokar.core.config.XdgPaths.of(name -> null, Path.of("/home/u"))))
                .isEqualTo(Path.of("/home/u/.config/sokar/ca-certificates.pem"));
    }

    @Test
    void aFileThatHoldsNoCertificateIsSaidRatherThanIgnored(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve(TrustedCertificates.FILE);
        Files.writeString(file, "not a certificate\n");

        assertThatThrownBy(() -> TrustedCertificates.context(file)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file.toString());
    }
}
