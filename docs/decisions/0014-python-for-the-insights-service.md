# ADR-0014: Write the insights service in Python

**Date:** 09-09-2026

**Service:** insights-service

## Context

The insights service answers questions about PayLedger in plain English. To do that it has to turn written text into numbers a computer can compare, search those numbers, and then ask a language model to write the answer.

Every well-supported tool for that first step is built for Python. The rest of PayLedger is Kotlin, so this is the first time the project has had to pick a language rather than inherit one.

The question is whether to keep one language across the whole repository, or accept a second one for this service alone.

## Decision

The insights service is written in Python, with FastAPI serving its single endpoint. The other two services stay in Kotlin, untouched.

## Alternatives considered

#### Option A - Keep it in Kotlin and use a Java library for the text-to-numbers step

**How it works**: bring in one of the machine learning libraries that runs on the same platform Kotlin does, and load the model through that

**Pros**:
- One language, one build tool and one way to run tests across the whole repository
- Anyone who can read the other two services can read this one

**Cons**:
- There is no equivalent of the Python library this service uses. What that library does in one line — load the model, split the text the way the model expects, produce the numbers — would have to be assembled by hand out of smaller parts
- The model itself is published for Python first. Using it anywhere else means converting it, and then owning that conversion every time it is updated
- Noticeably more code to write and keep working, for a result that is identical

#### Option B - A Kotlin service that calls a small Python program for the numbers

**How it works**: keep the service in Kotlin and hand off only the text-to-numbers step to a separate Python process alongside it

**Pros**:
- The endpoint, the database access and the searching all stay in the main language
- The Python part is small and could be swapped out later without touching the rest

**Cons**:
- Two programs and a call between them to do one job, and that call is now a thing that can fail on its own
- The Kotlin half ends up as little more than a pass-through, so the split buys the appearance of consistency rather than the substance of it
- Both halves still have to be built, shipped and started, so nothing is actually saved

## Consequences

**Gained**:
- Every tool this service needs is a first-class citizen rather than something worked around
- The service is small enough to read in one sitting, which would not be true of the hand-assembled version
- Being deliberately polyglot is a fair thing to be questioned about, as long as the reason is a real one rather than a preference

**Gave up/new risk**:
- A second set of dependencies to keep current, and a second way of building a container image
- No code can be shared with the other two services, so anything common has to be written twice on purpose
- The image is around 2 GB, because the machine learning library it depends on is large. The Kotlin services are a fraction of that
- Someone joining now needs both languages to work across all three services

**Revisit if**: the text-to-numbers step ever moves to a paid service running elsewhere. At that point nearly all of the reason for Python disappears, and this becomes an ordinary API that Kotlin would handle just as well.
