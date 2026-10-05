package org.fuin.sokar.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

/**
 * Supplies the version shown by {@code sokar --version}.
 * <p>
 * Read from a filtered resource rather than from the manifest, which a native image does not carry.
 */
public class SokarVersion implements IVersionProvider {

    /**
     * Returns the version this build was made from.
     * <p>
     * The daemon reports it over the wire, so an interface can show which Sokar it is talking to
     * and refuse a build it is too new for. Same resource as {@code --version}, because two
     * answers to "which version is this" is one too many.
     *
     * @return The version, or {@code "unknown"} when the resource is missing.
     */
    public static String version() {
        final Properties properties = new Properties();
        try (InputStream in = SokarVersion.class.getResourceAsStream("/sokar-version.properties")) {
            if (in == null) {
                return "unknown";
            }
            properties.load(in);
        } catch (IOException ex) {
            return "unknown";
        }
        return properties.getProperty("version", "unknown");
    }

    @Override
    public String[] getVersion() throws IOException {
        final Properties properties = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/sokar-version.properties")) {
            if (in == null) {
                return new String[] { "sokar (version unknown)" };
            }
            properties.load(in);
        }
        return new String[] { "sokar " + properties.getProperty("version", "unknown") };
    }
}
