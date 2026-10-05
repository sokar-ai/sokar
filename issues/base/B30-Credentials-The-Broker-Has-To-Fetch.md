# B30 — Credentials The Broker Has To Fetch

**Status:** soon; the key-based kinds wait for a service that needs them.

**What must be true.** A task uses a credential authenticated by a key rather than a secret -
`private_key_jwt`, or mTLS client authentication - with the broker performing the signing on the host, so
neither the key nor the token it buys is ever inside the container; and a `client_credentials` purchase is
seen to succeed end to end, not only in unit tests.

## Acceptance

- A task authenticates against a service that takes `private_key_jwt`, for longer than one bought token
  lasts, without the key leaving the vault - the way commit signing already works - and nothing in the
  container performs an exchange. Seen to fail: the key, the signed assertion or the bought token in the
  container's environment, filesystem or any command line that created it.
- The same for mTLS client authentication, or a stated reason why it cannot pass through a broker that
  terminates TLS itself.
- On a rented machine, a task holding a `client_credentials` entry reaches a service through an
  authorization server the broker trusts, and keeps working past the bought token's end. Seen to fail: a
  503 naming the authorization server, or a second exchange before the first token is a minute from its end.

## To be checked

- Whether `private_key_jwt` and mTLS are one mechanism or two: both have the broker operate with a key,
  but in different places in the connection.
- What a task that loses its bought token mid-run sees: a purchase failing at start is refused before a
  container exists, one failing during the run cannot be.
- Whether a stored credential that carries an end and nothing to renew it with should be named before a
  task needs it (a `vault check` for the kinds that carry the date).
