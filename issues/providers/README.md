# Provider Requirements

One file per provider, plus the one that is about providers as a class. Each carries its own
acceptance criteria so it can be judged done or not done.

**Status** is what exists today. **Shipped** means a task authenticates against it through the
broker, with the real credential never entering the container.

A provider is **data**: an upstream, a header, a prefix and a path, found by scanning a directory.
That is why there is no code to write for most of these, and why adding one is a file rather than
a release.

## Providers as a class

Ordered by what to do next, not by number: the number is only the file's identity.

| # | Requirement | Status | What it covers | Open question |
|---|---|---|---|---|
| P01 | [Providers As Packages](P01-Providers-As-Packages.md) | **built** | A provider is declared once and reused, rather than restated inside every agent that reaches it. | yes |

## One file per provider

| # | Provider | Status | Open question |
|---|---|---|---|
| P02 | [Anthropic](P02-Provider-Anthropic.md) | **shipped** | yes |
| P03 | [OpenRouter](P03-Provider-OpenRouter.md) | **shipped** | |
| P04 | [GitHub Copilot](P04-Provider-GitHub-Copilot.md) | chosen next | yes |
| P05 | [OpenAI](P05-Provider-OpenAI.md) | candidate | yes |
| P06 | [Google](P06-Provider-Google.md) | candidate | yes |
| P07 | [xAI](P07-Provider-xAI.md) | candidate | yes |
| P08 | [Zhipu](P08-Provider-Zhipu.md) | candidate | yes |

## Notes

Which agents can reach which of these, and which of them can be brokered at all, is surveyed in
[Agents And Providers Compared](../Agents-And-Providers-Compared.md).
