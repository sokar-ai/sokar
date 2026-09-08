# B21 — Instructions For An Agent

**Status:** open. Nothing on the contract touches instructions, and this is the one on the list
that changes what an agent actually does.

An agent can be given standing instructions at machine level and at project level. A project either
carries none of its own, **adds to** the shared ones, or **replaces** them — and from the outside
those three look identical. Somebody reading a project that quietly replaces the machine's
instructions has no way to see it.

## The part nobody else can compute

Read and write at both levels is the easy half. The half that cannot live in a client is **the
fully resolved text a task would actually receive.**

Resolving it on the client's side means inventing a merge rule the contract never stated. This
project has already made that mistake twice with joins, and it is worse here: a wrong merge does
not render a blank field, it silently changes what the agent is told to do.

## Acceptance

- Instructions are readable and writable at machine level and at project level.
- Which of the three positions a project is in — none, adds, replaces — is answered explicitly and
  not inferred from whether text is present.
- The resolved text a task would receive is available, computed by the daemon.
- What a running task was given is recoverable afterwards, because "why did it do that" is asked
  about tasks that are over.

## Notes

**Instructions are not a project-file field, and that is settled.** A project file describes
constraints; an instruction is what a model is told to do. Put one in a committed file and it
arrives with the repository — whoever can commit can put words in front of an agent that somebody
else starts, and nothing in a container makes a model treat a sentence as data rather than as an
order. That was decided when a stored "named job" was refused.

Where instructions do live is therefore part of this requirement rather than assumed.

## To be checked

- **Where machine-level instructions live**, given the above. A file under the node's own config is
  the obvious answer, and it is the one that does not travel with a repository.
- **Whether a project-level instruction is a path or a value.** A path keeps the text in the
  repository where it can be reviewed; a value keeps it out of the repository entirely. The
  argument above cuts towards the second, which is the less obvious answer.
