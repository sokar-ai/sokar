# dist

The packages Sokar is shipped in, and the script that prepares a machine for them. A module of packaging `pom` that
only groups them.

- [dist-deb](dist-deb/README.md) - the `.deb`.
- [dist-rpm](dist-rpm/README.md) - the `.rpm`.
- [dist-check](dist-check/README.md) - reads the packages the build wrote and checks their version.
- [package-check](package-check/README.md) - checks the `.deb` and the `.rpm` against each other and against a real install.
- `dist-setup/sokar-setup.sh` - not a module: it prepares a machine that has nothing installed, run as root.
