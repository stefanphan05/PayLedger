# ADR-0010: Test the two services against a shared example message

**Date:** 08-09-2026

**Service:** payment-api-service, ledger-service

## Context

The two services talk by passing messages. Each keeps its own description of what a message looks like, on purpose ([ADR-0003](0003-kafka-for-internal-service-messaging.md)). Nothing stops one side quietly changing the format and breaking the other.

The obvious fix is one test that runs both services together. That turns out to be expensive here: the two services have files with the same names in the same places, so they cannot be started inside a single test. Starting them as two separate programs does work — it was built, and it needed a lot of setup for what it proved.

## Decision

Two tests and one shared example message, saved as the same file in both services.

- The ledger runs a payment and checks the message it produced still matches the example.
- The API takes that example, sends it through a real message queue, and checks the payment finishes.

Change the message format on either side and one of the two tests fails.

## Alternatives considered

#### Option A - Run both services together as separate programs

**How it works**: a test starts both services, then uses the API like a real user would

**Pros**:
- The most realistic test possible
- Would have caught a setting that accidentally switched the API's message handling off

**Cons**:
- A lot of setup: extra project, starting and stopping programs, waiting for them to be ready
- About 90 seconds per run
- Easy to get wrong in a way that silently tests against the development databases and still passes

#### Option B - Start both services inside one test

**How it works**: boot both in the same test program

**Pros**: fast and simple to debug

**Cons**:
- Does not work. Both services have setup files with identical names, and only one of each gets used
- The only fix is renaming files in both services to suit a test, which is backwards


## Consequences

**Gained**:
- A format change on either side fails a test
- Both tests are ordinary tests living in the service they belong to. No extra project, and they run in seconds
- The API's test sends the message through a real queue, so the queue settings are covered too, not just the reading of the message

**Gave up/new risk**:
- The two services are never running at the same time, so problems that only appear when both are live are not covered. The switched-off setting mentioned above is one of those — this pair would not have caught it
- The example message is copied by hand into both services. Updating one and forgetting the other is possible, so both tests name the file when they fail
- Only the field names are checked, not the format of the values. Changing how a date is written would pass the test and still break in production

**Revisit if**: the services get packaged as containers in the future. Running them together becomes cheap at that point, and is worth adding alongside these tests to cover the problems they cannot see.
