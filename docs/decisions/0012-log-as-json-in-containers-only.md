# ADR-0012: Write logs as data in containers, and as text on your machine

**Date:** 09-09-2026

**Service:** payment-api-service, ledger-service

## Context

Log lines were plain sentences. A person can read them, but nothing else can: there is no reliable way to ask "show me every line for this payment" without guessing at the shape of the text.

That matters now for two reasons. [ADR-0011](0011-carry-the-correlation-id-in-a-kafka-header.md) gives every payment an id worth searching for, and searching is only dependable if each line is structured rather than free text. Feature 13 goes further and needs to group log lines by that id automatically.

The obvious move is to write every line as data instead of a sentence. The catch is that data is unpleasant to read. A single line becomes long and dense, and most of the time you are just watching a service start up on your own machine.

## Decision

Logs are written as data in the containers, and as ordinary readable text when a service runs directly on your machine.

This is one setting, provided only to the containers. Nothing in the code changes, and no configuration file was added.

The ids from ADR-0011 are included automatically, so no individual logging line has to mention them.

## Alternatives considered

#### Option A - Add a logging library and a configuration file

**How it works**: bring in the well-known third-party library for this, and write a configuration file describing the output

**Pros**:
- The usual approach, with plenty of examples to copy from
- Complete control over the shape of the output

**Cons**:
- An extra dependency plus a configuration file to maintain, for something the framework now does on its own with a single setting
- The configuration file is another thing that can drift between the two services

#### Option B - Write logs as data everywhere, including on your machine

**How it works**: turn the setting on permanently rather than only for containers

**Pros**:
- One behaviour to think about, and no chance of a difference between the two

**Cons**:
- Makes everyday work worse for no benefit. Watching a service start becomes a wall of dense text
- The audience is genuinely different: a person reads development logs, a machine reads the ones from containers. Optimising both for the machine helps nobody

## Consequences

**Gained**:
- Every line is one searchable record rather than a sentence to be picked apart
- The payment id and the request id ride along on their own, so no logging line has to remember to include them
- Each line names the service that produced it, so the two services stay distinguishable when their logs are read together
- No new dependency and no configuration file

**Gave up/new risk**:
- The two environments now behave differently, so a problem with the output shape can only show up in containers, never while developing
- The banner printed when a service starts is still plain text. That is normal, but it does mean the very first lines are not searchable
- Anything written into a log line from outside the system could in principle disturb the structure. The id from ADR-0011 is checked for exactly this reason; anything else added later needs the same care

**Revisit if**: logs are ever sent somewhere that expects a different arrangement. The format is a one-word change, so this is cheap to undo. Also worth revisiting if the difference between the two environments ever causes a real problem — at that point, matching them is better than debugging the gap.
