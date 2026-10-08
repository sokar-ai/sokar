# dist-deb

Builds the `.deb` that ships Sokar: the executables of `apps/app`, `daemon` and `hooks`, the providers and egress
sets, the SELinux policy, the systemd user unit and the bill of materials. It builds no executable itself.

- Built with `-Pdist`; the package lands in `target/`. See [building](../../doc/build.md).
