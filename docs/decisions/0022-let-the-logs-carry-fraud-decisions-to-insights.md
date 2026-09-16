# ADR-0022: Let the logs carry fraud decisions to insights

**Date:** 16-09-2026

**Service:** fraud-service, insights-service

## Context

Once payments could be held or refused, the obvious next question was *"why was this one blocked?"* — and there is already a service whose entire job is answering questions like that.

The tempting design is for `fraud-service` to ask `insights-service` for a plain-English explanation whenever it holds a payment, and store the answer next to the decision.

There is a catch. `insights-service` answers from a searchable copy of the logs and documents that is rebuilt by hand. A payment made since the last rebuild is invisible to it, and the service cannot tell you that. So asking it about a payment it has never seen produces a fluent answer about nothing.

## Decision

No connection between the two services. `fraud-service` writes its decision to the log as a readable sentence containing the score and the rules that fired, and that line reaches the searchable copy the same way every other log line does.

Asking "why was this blocked?" is an ordinary question to `insights-service`, asked when someone wants to know.

## Alternatives considered

#### Option A - fraud-service asks insights for an explanation

**How it works**: on every held or refused payment, call `insights-service` and store the answer against the decision

**Pros**:
- The explanation is waiting when a reviewer opens the queue, rather than having to be asked for
- Reads well in a demo

**Cons**:
- It would be asking about a payment that is almost certainly not in the searchable copy yet, because that copy is rebuilt by hand. The answer would be confident and hollow
- Adds a dependency, a queue of pending explanations, retries, and a rule for what happens when the answer never arrives — for something nobody has asked for yet
- Writes a language model's output into the fraud database, where it sits next to facts and looks equally authoritative

#### Option B - Push decisions into the searchable copy directly

**How it works**: fraud decisions become searchable entries alongside logs and documents

**Pros**:
- Decisions become searchable in their own right, not just as log text
- Questions like "what patterns have we seen this week" become answerable

**Cons**:
- The rebuild empties and refills the whole store, so anything added outside it is wiped next time
- Means changing `insights-service`, in a different language, to teach it a new kind of source

## Consequences

**Gained**:
- Neither service knows the other exists. No dependency, no timeout, no retry logic, nothing to go wrong
- The explanation is always as fresh as the logs, and stale in exactly the way the reader already expects logs to be stale
- Nothing was added to `insights-service` at all

**Gave up/new risk**:
- Nobody gets an explanation until the searchable copy is rebuilt by hand. That is a real gap, and it is inherited from how that service already works rather than introduced here
- The decision log line is now load-bearing in a way that is invisible from the code. The reader keeps only the message text, so a score moved into a structured field instead of the sentence would vanish from every answer without failing anything. There is a test asserting the score and rule names appear in that line, and it is the only thing guarding this
- The line has to stay short. The reader truncates long entries silently, and a payment's log flow is close enough to the limit that a wordy decision line would push the end of the story out

**Revisit if**: the searchable copy stops being rebuilt by hand. Automatic ingestion would make an explanation-on-arrival design work properly, and at that point option A becomes reasonable rather than hollow.
