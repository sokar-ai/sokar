# B134 — A Task Terminal As The Terminal Outside

**Status:** decided; what differs is measured first.

**What must be true.** An agent in a task behaves at the keyboard and on the screen as it does in a plain terminal on
the same machine. Scrolling, Esc, modified keys, colours, the clipboard, links, focus and images work as they do
outside. Where something cannot, it is written down, with why.

## Why

On 2026-10-09 the operator found that PageUp and PageDown do not scroll in Oh My Pi inside a task, though they do in
a plain console. It holds for every agent, since each runs in the one tmux session the task image sets up.
`/etc/sokar/tmux.conf` (`runtime/.../Containerfile.java`) holds `history-limit 10000`, `extended-keys on`,
`terminal-features 'xterm*:extkeys'` and `default-terminal tmux-256color`, and nothing for scrolling, Esc, colours,
the clipboard or passthrough.

## What is suspected, to be measured, not assumed

    behaviour                       plain terminal         in the task's tmux today          what would close it
    scrolling (PageUp, Shift+PgUp,  the terminal's own     tmux's history, reached only      mouse on; PageUp into copy mode
      mouse wheel)                    scrollback             through the prefix and [          unless the pane is in the
                                                                                               alternate screen
    Esc                             at once                after escape-time, 500 ms in      escape-time 0 or 10
                                                           tmux 3.4; agents interrupt on Esc
    Shift+Enter, Ctrl+arrows, ...   the terminal's codes   translated by tmux                extended-keys always;
                                                                                               extended-keys-format csi-u
                                                                                               (tmux 3.5 and later)
    colours                         truecolor              256 unless told                   terminal-features RGB
    clipboard (OSC 52), links,      passed                 filtered                          set-clipboard on; terminal-features
      focus events                                                                             clipboard, hyperlinks, focus;
                                                                                               focus-events on
    images, other passthrough       passed                 blocked                           allow-passthrough on

Two terminals are involved. One is **outside**: whatever the person uses, or the interface. tmux uses its
capabilities only where `terminal-features` names them. The other is **inside**, the one the agent sees
(`tmux-256color`). tmux's version comes from the project's base image: `ubuntu:24.04`, the default, has 3.4,
without `extended-keys-format`.

## The shape

1. **Measured first, on the Ubuntu VM:** every row above for Claude Code, Pi and Oh My Pi, once in a plain terminal
   and once in a task, with the same outside terminal. Also whether the agent handles PageUp itself: a full-screen
   agent in the alternate screen may scroll its own view, and then tmux must pass the key, not take it. The result is
   a table in this issue: what differs, per agent.
2. **Then the configuration** that closes what differs, written into `tmux.conf` by `Containerfile`, fitted to the
   image's tmux. An option a tmux lacks is set with `-q`, as `extended-keys` is today, and the measurement says
   what that costs on 3.4.
3. **tmux stays.** It carries reattaching, the `at_rest` screen reading and the wake lines. A relay that passes raw
   bytes without a terminal of its own comes up only if configuration cannot get there, and then as a proposal to
   the operator, with what would be lost.

## Acceptance

- A test per key and capability, in the acceptance kit: send the key or sequence into a task's session and read what
  the program inside received, against what a plain terminal delivers. A difference is caught when it comes back.
- `ContainerfileTest` pins the configuration lines, as it pins today's.
- Seen to fail first: PageUp in a task today, against the measured plain-terminal behaviour.
