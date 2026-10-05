# B116 — A Log Read Back In Windows

**Status:** later.

**What must be true.** A client that started reading a task's log from its end can read what lies before, a window
at a time, and no single line of a log can make a reply as large as the log itself, because the daemon caps a
line's length and says how much it cut.

## Acceptance

- `Tail` given an offset and a count answers the lines that end at that offset, with the offset its first line
  starts at, so repeated calls walk back to the log's first line. Seen to fail: a window that repeats or skips a
  line at its edge, or a walk back over a log of several megabytes that does not end at offset 0.
- A log holding one line of many megabytes is answered with that line cut at the daemon's cap, and the reply
  names the length it cut. Seen to fail: a reply carrying the whole line, or a cut line with no length beside it.
