# Typed Actors vs Pekko Streams

## Current State: Typed Actors

Yes, we're using **Pekko Typed Actors**:

```java
public class TubeStatusReplicator extends AbstractBehavior<Command> {

    public sealed interface Command {}  // Type-safe messages
    public record GetStatus(ActorRef<StatusResponse> replyTo) implements Command {}

    @Override
    public Receive<Command> createReceive() { ... }
}
```

Type safety at compile time via sealed interfaces.

---

## The Question

Should we raise abstraction to **Pekko Streams**?

```
Actors:  Message → Actor → Message → Actor → Message
Streams: Source ~> Flow ~> Flow ~> Sink (with backpressure)
```

---

## Comparison

| Aspect | Typed Actors | Pekko Streams |
|--------|--------------|---------------|
| **Paradigm** | Message passing, stateful | Data flow, stateless |
| **Backpressure** | Manual (mailbox bounds) | Built-in (async boundary) |
| **State** | Natural (actor encapsulates) | Awkward (need statefulMapConcat) |
| **Request-Response** | Natural (ask pattern) | Awkward (need Source.single) |
| **Distribution** | Easy (remote actors) | Hard (streams are local) |
| **Composition** | Behavior composition | Operator composition |
| **Fault tolerance** | Supervision trees | Stream supervision |
| **Learning curve** | Moderate | Steep (many operators) |

---

## Our Use Case Analysis

### TubeStatusReplicator

**What it does:**
- Holds state (currentStatus, refreshInFlight, waiters)
- Request-response (GetStatus → StatusResponse)
- Interacts with distributed CRDT Replicator
- Periodic refresh (timer-driven)

**Verdict: Actors are the right fit**

Why:
1. **Stateful** - Actor encapsulates state naturally
2. **Request-response** - Ask pattern is idiomatic
3. **Distributed** - Talks to Pekko Distributed Data (actor-based)
4. **Timer-driven** - `Behaviors.withTimers` is clean

Streams equivalent would be awkward:
```java
// Awkward: stateful stream with request-response
Source.queue()
    .via(Flow.create().statefulMapConcat(() -> {
        // Manage state in closure - ugly
        TubeStatus[] current = new TubeStatus[1];
        return request -> {
            if (isFresh(current[0])) return List.of(current[0]);
            // How to async fetch here? Need mapAsync...
        };
    }))
    .to(Sink.foreach(response -> ...))
```

### HTTP Layer (Pekko HTTP)

**What it does:**
- Request → Route → Response

**Verdict: Already Streams-based**

Pekko HTTP is built on Streams:
```java
// This IS a stream internally
Http.get(system)
    .newServerAt("0.0.0.0", 8080)
    .bind(routes)  // Routes are Flow<HttpRequest, HttpResponse>
```

We're already using Streams where appropriate.

### TfL API Client

**What it does:**
- HTTP request → Parse → TubeStatus

**Could be Streams:**
```java
Source.single(HttpRequest.create(url))
    .via(http.superPool())
    .map(response -> parse(response))
    .runWith(Sink.head(), materializer)
```

**Currently (async future):**
```java
http.singleRequest(request)
    .thenApply(response -> parse(response))
```

Both work. `singleRequest` is simpler for one-shot requests.

**Verdict: Current approach is fine**

Streams shine for:
- Processing many requests
- Continuous data flows
- Complex transformations

We're doing one request at a time. `singleRequest` is appropriate.

---

## Where Streams WOULD Add Value

### Scenario: Continuous TfL Feed

If TfL provided a WebSocket/SSE stream (they don't):

```java
Source<TubeStatus, NotUsed> tflStream =
    Source.fromPublisher(tflWebSocket)
        .via(parseJson())
        .via(validateStatus())
        .via(enrichWithMetadata())
        .buffer(100, OverflowStrategy.dropHead())  // Backpressure
        .to(Sink.foreach(status -> crdtReplicator.tell(new Update(status))))
```

This would be a great Streams use case.

### Scenario: Batch Historical Processing

If we processed historical data for analysis:

```java
Source.from(historicalRecords)
    .grouped(100)  // Batch
    .mapAsync(4, batch -> processInParallel(batch))
    .runWith(Sink.fold(Stats.empty(), Stats::combine), materializer)
```

### Scenario: Fan-out to Multiple Consumers

```java
Source<TubeStatus, NotUsed> statusSource = ...

RunnableGraph.fromGraph(GraphDSL.create(builder -> {
    var broadcast = builder.add(Broadcast.create(3));

    builder.from(statusSource).to(broadcast);
    builder.from(broadcast).to(alertingSink);
    builder.from(broadcast).to(metricsSink);
    builder.from(broadcast).to(storageSink);

    return ClosedShape.getInstance();
}));
```

---

## Hybrid Approach

**Best of both worlds:**

```
┌─────────────────────────────────────────────────────────────┐
│                    HTTP Layer (Streams)                     │
│  Request ──► Route ──► Response                             │
└─────────────────────────────┬───────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              Business Logic (Typed Actors)                  │
│  TubeStatusReplicator ◄──► CRDT Replicator                 │
│  (stateful, request-response, distributed)                  │
└─────────────────────────────┬───────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                TfL Client (Async Futures)                   │
│  http.singleRequest() ──► CompletionStage                   │
│  (one-shot requests, simple)                                │
└─────────────────────────────────────────────────────────────┘
```

Each layer uses the right abstraction:
- **Streams** for HTTP (request pipeline, backpressure)
- **Actors** for state and coordination
- **Futures** for simple async operations

---

## Decision Matrix

| Component | Use Actors | Use Streams | Use Futures |
|-----------|------------|-------------|-------------|
| HTTP server | | ✓ (Pekko HTTP) | |
| Business logic (TubeStatusReplicator) | ✓ | | |
| CRDT integration | ✓ (required) | | |
| TfL client | | | ✓ |
| Metrics collection | | ✓ (if continuous) | |
| Batch processing | | ✓ | |

---

## If We HAD to Use Streams Everywhere

It's possible but verbose:

```java
// Actor-like behavior in Streams (not recommended)
Flow<Command, Response, NotUsed> replicatorFlow = Flow.create()
    .statefulMapConcat(() -> {
        // All state in closure
        AtomicReference<TubeStatus> current = new AtomicReference<>();
        AtomicBoolean refreshing = new AtomicBoolean(false);
        List<CompletableFuture<Response>> waiters = new ArrayList<>();

        return command -> {
            if (command instanceof GetStatus get) {
                if (isFresh(current.get())) {
                    return List.of(new Response(current.get()));
                }
                // Trigger refresh... but how async?
                // Need mapAsync, but we're in statefulMapConcat...
                // This gets ugly fast
            }
            return List.of();
        };
    });
```

**Problems:**
- State in closures is error-prone
- Async operations inside stateful operators are awkward
- No supervision tree equivalent
- Can't distribute across nodes

---

## Recommendation

**Keep current architecture:**

1. **Actors for stateful coordination** - TubeStatusReplicator
2. **Streams where we get them for free** - Pekko HTTP
3. **Futures for simple async** - TfL client

**Don't force Streams** where Actors are more natural.

**Add Streams** if we later need:
- Continuous data feeds
- Complex data pipelines with backpressure
- Fan-out/fan-in patterns

---

## Interview Talking Points

**"Why Actors over Streams?"**

> "Actors are the right fit for stateful, request-response, distributed coordination. Our replicator holds state, responds to queries, and talks to the distributed CRDT layer. Streams excel at stateless data pipelines with backpressure - we're already using them in Pekko HTTP. We use each abstraction where it's strongest."

**"Could you refactor to pure Streams?"**

> "Technically yes, but it would be fighting the abstraction. Stateful stream operators are awkward, and Pekko Distributed Data is actor-based. The hybrid approach - Streams for HTTP, Actors for coordination - is idiomatic and plays to each tool's strengths."
