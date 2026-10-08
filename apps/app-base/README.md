# app-base

The shared ground of the command line's and the daemon's areas: the context, the paths and the records every area
uses, in the package `org.fuin.sokar.app`. It holds no area of its own.

- Depends on no area; every area depends on it. Its paths are `SokarPaths`, its contract part `10-common`.
