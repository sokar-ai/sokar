/**
 * The service provider interface every agent module implements.
 * <p>
 * Nothing here names an agent, and nothing outside {@code agents/} depends on an agent. The
 * arrow points one way only: an agent knows about Sokar, Sokar does not know about agents.
 */
@NullMarked
package org.fuin.sokar.agent.api;

import org.jspecify.annotations.NullMarked;
