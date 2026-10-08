# dist-check

Reads the `.deb` and `.rpm` this build wrote - Sokar's and the stub agent's - and holds each to the version the
packaging must give it: the project's version with `-SNAPSHOT` as `~snapshot.<run><suffix>`, and the `.rpm`'s release
`1`. It runs only with `-Pdist`, after the packages are written, and needs `dpkg-deb` and `rpm`.
