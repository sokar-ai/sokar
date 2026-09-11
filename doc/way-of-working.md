# Three Ways of Working with AI Coding Helpers

---

## First, what are we talking about?

Software is written as long lists of precise instructions for a computer.
Today, people who write software increasingly get help from **AI coding
agents**. An agent is a program you can talk to in normal language ("please
add a search box to the shop page") that then writes the instructions itself,
tries them out, and shows you the result.

Think of an agent as a **very fast, tireless apprentice**. It can do a lot of
work by itself, but it sometimes misunderstands, and someone still has to
check what it did.

People usually compare these tools by **how much the apprentice does on its
own**, from "suggests the next word" up to "does the whole job while you
sleep". Several people have drawn this as a ladder
(see [Background](#background-the-ladders-of-how-much-the-ai-does-alone)).

A different question tells you more:

> **What is the tool built around? What does it put at the center?**

There are three possible centers: the **project**, the **agent**, and the
**person**. Those are the three "centric" ways of working.

---

## The running example

To keep things concrete, we follow one person through all three ways of
working.

**Sam is a freelance software developer.** Sam looks after:

- the online shop of a **bakery**,
- the booking app of a **hair salon**,
- a small internal tool for a **sports club**.

Sam works on a laptop at home, a desktop in a shared office, and sometimes
checks in from a phone. Sam uses several AI helpers at once.

---

## 1. Project-centric: "the factory"

### The idea

The tool is built around **one job in one project**. You write a work order,
hand it over, and get back a finished piece of work to inspect. You are not
involved while the work happens.

In software terms: the project's code lives in a shared storage place (a
*repository*). You write a short task description (an *issue*). The AI agent
takes it, works on it by itself somewhere in the cloud, and returns a proposed
change (a *pull request*) that you review and accept or reject.

### Everyday comparison

**Ordering from a professional workshop.** You fill in a form: "Replace the
dripping tap in the upstairs bathroom." A technician comes, does the job, and
you only look at the result and sign it off. You never stand next to them
while they work.

### Sam's example

A security problem is found in a widely used building block that all three of
Sam's projects use. Each project needs the same small, well-understood update.

Sam writes three short work orders ("update the building block to the fixed
version"). Three AI agents each take one, do the work, and hand back three
finished changes. Sam checks each one and approves it. Sam spent minutes, not
hours.

### Good at

- Clearly described, repetitive, bounded work: updates, small fixes, routine
  maintenance.
- Doing a lot of it in parallel. Dozens or hundreds of jobs can run at the
  same time.

### Weak at

- Anything that needs judgment, back-and-forth, or understanding across
  several projects. Each work order sees only its own project.
- **Checking becomes the new bottleneck.** The factory can produce far more
  finished work than a person can carefully inspect.

### Example tools

Devin, Google's Jules, OpenAI's Codex cloud agent, GitHub's Copilot coding
agent, Factory's Droids, OpenHands.

---

## 2. Agent-centric: "the cockpit"

### The idea

The tool is built around **one working session with an AI helper**. You sit
in one well-designed workspace and work closely with the agent: you explain,
it does something, you look, you correct, it continues. You are present the
whole time.

### Everyday comparison

**Working side by side with a skilled craftsperson in their workshop.** You
are restoring an old piece of furniture together. You point: "a bit more off
here, careful with that corner." They do it right away, you look together,
and decide the next step. Everything you need is within reach in that one
room.

### Sam's example

The bakery wants to change how customers pay: they should be able to order
cakes in advance, pay a deposit, and pick a pickup time. This is tricky. It
touches many parts of the shop and there are many small decisions ("what if
the customer cancels?").

Sam opens a session with an AI agent and works through it together with the
agent for an afternoon: discussing, trying, correcting, until it is right.

### Good at

- Deep, focused work on one hard change. A single, well-designed workspace
  is the best environment there is for focused work.

### Weak at

- **Lock-in.** The better the workshop, the more its owner wants you to use
  only their tools, their accounts and their AI. Vendors restrict access so
  that you stay inside their own world.
- It only sees the one session you are in. It does not know what else is
  going on in Sam's day.

### Example tools

Claude Code, Cursor, Sourcegraph's Amp. Tools like Conductor, Claude Squad
and Vibe Kanban let you run several such cockpits side by side on your own
computer.

---

## 3. Person-centric: "the control tower"

### The idea

The tool is built around **you, the human**. It is not tied to one project,
one tool, one company or one computer. It gives you a single place that sees
all your AI helpers, wherever they are working, and tells you where you are
needed. You stay in charge; the tool routes work, questions and approvals to
you.

### Everyday comparison

**An air traffic control tower.** The tower does not fly the planes, and the
planes belong to different airlines. But the tower sees all of them at once,
knows which one needs attention right now, and gives the go-ahead at the
right moment.

Or, closer to home: **a personal assistant who keeps your whole calendar**,
across work, family and clubs, instead of each organization keeping its own
separate calendar that you have to check one by one.

### Sam's example

It is Tuesday afternoon. Sam has eight AI helpers running:

- three factory jobs (the security updates from example 1),
- the bakery payment session on the laptop at home,
- two sessions for the hair salon app on the office desktop,
- two for the sports club.

Without a control tower, Sam has to remember what runs where, and walk
between screens and machines to check. Meanwhile one helper has been sitting
for an hour waiting for a simple "yes, go ahead", and one factory job has
finished and waits for review.

With a person-centric tool, Sam sees one overview, on any device, even the
phone: "Hair salon session on the office desktop is waiting for your
approval. Security update for the sports club is ready for review." Sam
answers from wherever they are.

### Good at

- Fixing the fragmentation of a real working day: many projects, many tools,
  many machines, sometimes many clients.
- Letting one person keep an overview without becoming the bottleneck.

### Weak at

- It lacks the safety nets that the big platforms have built in, such as
  automatic checks and review steps.
- It depends on the very companies whose borders it crosses. Like a travel
  agent booking across airlines, it only works as long as each airline lets
  it in.

### Example tools

Very few exist. One is the experimental
[ai-beacon](https://github.com/manusa/ai-beacon), a web dashboard that shows
every coding agent on every machine and notifies you when one needs you.
GitHub's Agent HQ ("mission control" for agents from several AI companies)
goes partly in this direction, but it is still centered on GitHub.

### Why are there so few?

Not because nobody has thought of it, but because of business interests.
Every vendor's center of gravity is **their project platform or their
product**, because that is where they earn money. No big company has a reason
to build a tool whose center is *you* and that works equally well with its
competitors. So people who need it tend to build it themselves.

---

## Not rivals, but layers

The key point: you do not pick one of the three. They fit together, like
**a control tower over a factory floor**:

- The **factory** (project-centric) does the routine work at scale.
- The **cockpit** (agent-centric) is where you go for deep, careful work on
  one hard problem.
- The **control tower** (person-centric) sits above both and keeps you, the
  human, in charge of the whole picture.

### Which one when? A simple rule

| If the work is...                                           | Use...            | Everyday picture              |
|-------------------------------------------------------------|-------------------|-------------------------------|
| one hard change that needs your close attention             | **the cockpit**   | working side by side          |
| clearly described and can be handed off                     | **the factory**   | sending out a work order      |
| spread over several projects, tools or machines             | **the control tower** | the air traffic controller |

### Inner loop and outer loop

OpenHands describes a related split
([source](https://openhands.dev/blog/20251202-agents-in-the-outer-loop)).
The **inner loop** is a developer's own hands-on work at their desk:
changing, trying, adjusting. The **outer loop** is everything after the work
leaves the desk: automatic checks, reviews by colleagues, release. In cooking
terms, the inner loop is preparing the food in your kitchen; the outer loop
is serving it and getting feedback from the guests.

This lines up with the three ways of working. Cockpit tools help in the
**inner loop** (ad hoc work that needs judgment). Factory tools shine in the
**outer loop** (repeatable chores that can run by the hundreds). The control
tower is what lets one person keep track of both.

---

## Where Sokar fits

**Sokar is the safe building that the cockpit and the factory work in, with
the control tower on top, and all of it stands on your own machines.**

Sokar is not an AI helper itself. It takes the helpers you already use, such
as Claude Code, Codex or Gemini, and runs every job in a sealed room: no
internet except what the project allows, no real passwords inside, and
nothing leaves until you have approved it.

- **Cockpit:** work side by side with whichever helper you like, inside that
  sealed room. Helpers are interchangeable add-ons, so no single vendor locks
  you in.
- **Factory:** hand off a job and walk away. Because the room is sealed,
  working unattended is safe, and every result waits in an in-tray (the
  *gate*) for your review, on your machine instead of in a vendor's cloud.
- **Control tower:** one app shows every job on every machine, tells you which
  one is waiting for you, and lets you answer without going to find it. It also
  brings the safety nets a control tower usually lacks: nothing goes out without
  review, and everything blocked is recorded. Today that app is a desktop one
  and the machines it reaches are reached over ssh; answering from a phone is
  where this is going, not where it is.

What Sokar cannot change: each AI company still decides how its helper may
sign in. If a vendor closes that door, Sokar feels it too.

---

## Background: the "ladders" of how much the AI does alone

There are a number of popular ways of ranking AI coding
tools by **autonomy**, meaning how much the AI does by itself. They help to
understand the three ways of working above.

- **Dan Shapiro's five levels** compare it to cars
  ([source](https://www.danshapiro.com/blog/2026/01/the-five-levels-from-spicy-autocomplete-to-the-software-factory/)):
  from driving a manual car yourself, via cruise control and highway
  autopilot, to a robotaxi, and finally a "dark factory", a fully automated
  factory where no human is needed and the lights can stay off. At the higher
  levels, the human stops writing and becomes a reviewer, and then someone
  who only describes what they want.
- **Steve Yegge's eight levels**
  ([source](https://newsletter.pragmaticengineer.com/p/steve-yegge-on-ai-agents-and-the))
  go from no AI at all, through one helper you watch closely, to running ten
  or more helpers at once by hand (which gets chaotic), and finally building
  your own system to coordinate them.
- **Swarmia's five levels**
  ([source](https://www.swarmia.com/blog/five-levels-ai-agent-autonomy/))
  add a useful warning: **higher is not always better**. Giving an expert
  intern-level tasks wastes them; putting a beginner in charge of everything
  is a disaster. Pick the level that fits how clear and well-bounded the task
  is.
- **Addy Osmani** splits the ladder into **two separate questions**
  ([source](https://addyo.substack.com/p/agentic-autonomy-levels)): how
  independently *one* helper works, and how well *many* helpers are
  coordinated. In a restaurant kitchen: how much can one cook decide on their
  own, and how well does the head chef run the whole team? A good kitchen
  needs both.
- **Marc Nuri's missing levels**
  ([source](https://blog.marcnuri.com/missing-levels-ai-assisted-development))
  fill the big jump from "juggling many helpers by hand" to "building your
  own coordination system" with smaller steps: **see** all helpers in one
  place, reach them **from any device**, **hand off** work, and decide in
  advance **which changes need your approval**. Much like a manager whose
  team keeps growing. His conclusion: **the hard part is no longer producing
  the work, it is checking it.**

---

## Small glossary

| Word               | Plain meaning                                                                 |
|--------------------|-------------------------------------------------------------------------------|
| **AI agent**       | An AI program that doesn't just answer, but also does the work itself.       |
| **Harness**        | The program around the AI model that lets it act: it gives the model tools, keeps track of the conversation and asks you before risky steps. Model + harness = agent (like engine + car = something you can drive). |
| **Repository**     | The shared storage place holding all the code of one project.                |
| **Issue**          | A written task or problem report: a work order.                              |
| **Pull request**   | A proposed change handed in for someone to review before it is accepted.     |
| **Session**        | One continuous conversation and piece of work with an AI agent.              |
| **Orchestration**  | Coordinating many helpers so that they work together without chaos.         |
| **Lock-in**        | Being tied to one company's tools because leaving would be hard or costly.   |
| **Verification**   | Checking that the work is correct before trusting it.                        |

---

## Sources

- Marc Nuri: [Project, Agent, Person: The Missing Axis of AI Coding Tools](https://blog.marcnuri.com/project-agent-person-centric-ai-coding-tools)
- Marc Nuri: [The Missing Levels of AI-Assisted Development](https://blog.marcnuri.com/missing-levels-ai-assisted-development)
- Marc Nuri: [AI Coding Agent Dashboard](https://blog.marcnuri.com/ai-coding-agent-dashboard)
- Steve Yegge via The Pragmatic Engineer: [Steve Yegge on AI agents](https://newsletter.pragmaticengineer.com/p/steve-yegge-on-ai-agents-and-the)
- Dan Shapiro: [The five levels: from spicy autocomplete to the software factory](https://www.danshapiro.com/blog/2026/01/the-five-levels-from-spicy-autocomplete-to-the-software-factory/)
- Swarmia: [Five levels of AI agent autonomy](https://www.swarmia.com/blog/five-levels-ai-agent-autonomy/)
- Addy Osmani: [Agentic autonomy levels](https://addyo.substack.com/p/agentic-autonomy-levels)
- OpenHands: [Agents in the outer loop](https://openhands.dev/blog/20251202-agents-in-the-outer-loop)
- GitHub: [Welcome home, agents (Agent HQ)](https://github.blog/news-insights/company-news/welcome-home-agents/)
- [ai-beacon](https://github.com/manusa/ai-beacon) on GitHub
