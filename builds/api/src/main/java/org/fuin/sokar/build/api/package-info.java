/**
 * The contract between Sokar and a build reader: the interface a reader implements, the server it runs, and the client
 * Sokar asks it with. A reader in its own repository depends on this and on {@code sokar-wire}, and on nothing else of
 * Sokar's.
 */
@NullMarked
package org.fuin.sokar.build.api;

import org.jspecify.annotations.NullMarked;
