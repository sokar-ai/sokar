# stub

The build reader the tests read builds from, so they need no forge on the network; its forge is `stub-forge`, and it answers from an `answers.json` beside it. Only for tests and the acceptance suite.

`heads` in it maps a branch at the forge to its head commit. That is the branch the task's work reaches there - for an online task `sokar/<task>`, not the task's name.
