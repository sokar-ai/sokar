# Provider Requirements

One file per provider, plus those about providers as a class. Each carries its own
acceptance criteria so it can be judged done or not done.

**Status** is what exists today. The providers Sokar ships, each reached through the broker with the
real credential never entering the container, are in [Credentials](../../doc/credentials.md#the-providers-sokar-ships).

A provider is **data**: an upstream, a header, a prefix and a path, found by scanning a directory.
That is why there is no code to write for most of these, and why adding one is a file rather than
a release.

## Providers as a class

Ordered by what to do next, not by number: the number is only the file's identity.

| # | Requirement | Status | What it covers | Open question |
|---|---|---|---|---|
| P09 | [A Native Agent And A Provider It Does Not Know](P09-A-Native-Agent-And-A-Provider-It-Does-Not-Know.md) | open | Whether an agent speaking `native` reaches a provider whose name it does not recognize. | yes |

## One file per provider

| # | Provider | Status | Open question |
|---|---|---|---|
| P05 | [OpenAI](P05-Provider-OpenAI.md) | candidate | yes |
| P06 | [Google](P06-Provider-Google.md) | candidate | yes |
| P07 | [xAI](P07-Provider-xAI.md) | candidate | yes |
| P08 | [Zhipu](P08-Provider-Zhipu.md) | candidate | yes |

## Notes

Which agents can reach which of these, and which of them can be brokered at all, is surveyed in
[the comparison of agents and providers](https://github.com/sokar-ai/sokar-project/blob/main/doc/agents-and-providers.md) in `sokar-project`.
