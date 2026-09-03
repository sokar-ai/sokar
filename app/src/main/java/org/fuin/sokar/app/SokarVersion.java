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
