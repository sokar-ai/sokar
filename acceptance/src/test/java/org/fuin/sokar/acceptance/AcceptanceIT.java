package org.fuin.sokar.acceptance;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * Runs the feature files.
 * <p>
 * A JUnit suite so that Maven reports each scenario as a test rather than as a script's standard
 * output - which is the other half of what this module was asked for: an overview of what is
 * tested, without anybody reading a log.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("org/fuin/sokar/acceptance")
@ConfigurationParameter(key = "cucumber.glue", value = "org.fuin.sokar.acceptance")
public class AcceptanceIT {
}
