# Actor & Data Diagram

## Legend

```
┌─────────────┐
│   ACTOR     │   Rectangle = Actor
└─────────────┘

╔═════════════╗
║ SHARED DATA ║   Double-line box = Shared (CRDT replicated)
╚═════════════╝

┌ ─ ─ ─ ─ ─ ─ ┐
  PRIVATE DATA    Dashed box = Actor-private state
└ ─ ─ ─ ─ ─ ─ ┘
```

---

## Single Node View

```
                            ┌──────────────────────────────────────────────┐
                            │                    NODE                      │
                            │                                              │
   HTTP Request             │  ┌─────────────────────────────────────┐    │
        │                   │  │         TubeStatusReplicator        │    │
        │                   │  │              (Actor)                │    │
        ▼                   │  │                                     │    │
┌───────────────┐           │  │  ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐  │    │
│  HTTP Routes  │◄──────────┼──┤    currentStatus: TubeStatus      │    │
│   (Handler)   │  ask      │  │  │ (local copy for fast reads)  │  │    │
└───────────────┘           │  │   ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─   │    │
        │                   │  │                                     │    │
        │                   │  │  ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐  │    │
        │                   │  │    refreshInFlight: boolean        │    │
        │                   │  │  │ waiters: List<ActorRef>      │  │    │
        │                   │  │   ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─   │    │
        │                   │  └──────────────┬──────────────────────┘    │
        │                   │                 │                           │
        │                   │                 │ read/write                │
        │                   │                 ▼                           │
        │                   │  ┌─────────────────────────────────────┐    │
        │                   │  │      Pekko Replicator (Actor)       │    │
        │                   │  │   (built-in, handles CRDT ops)      │    │
        │                   │  │                                     │    │
        │                   │  │  ╔═══════════════════════════════╗  │    │
        │                   │  │  ║   LWW-Register<TubeStatus>    ║  │    │
        │                   │  │  ║      (CRDT - SHARED)          ║  │    │
        │                   │  │  ║                               ║  │    │
        │                   │  │  ║  key: "tube-status"           ║  │    │
        │                   │  │  ║  value: TubeStatus            ║  │    │
        │                   │  │  ║  timestamp: VectorClock       ║  │    │
        │                   │  │  ╚═══════════════════════════════╝  │    │
        │                   │  └──────────────┬──────────────────────┘    │
        │                   │                 │                           │
        │                   │                 │ gossip protocol           │
        │                   │                 ▼                           │
        │                   │         ═══════════════════                 │
        │                   │          To other nodes                     │
        │                   │         ═══════════════════                 │
        │                   │                                              │
        │                   │  ┌─────────────────────────────────────┐    │
        │                   │  │         TflApiClient                │    │
        │                   │  │         (not an actor)              │    │
        │                   │  │                                     │    │
        │                   │  │  ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐  │    │
        │                   │  │    CircuitBreaker state            │    │
        │                   │  │  │   - consecutiveFailures      │  │    │
        │                   │  │  │   - state: CLOSED/OPEN/HALF  │  │    │
        │                   │  │  │   - openedAt: Instant        │  │    │
        │                   │  │   ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─   │    │
        │                   │  └──────────────┬──────────────────────┘    │
        │                   │                 │                           │
        │                   │                 ▼                           │
        │                   │           ┌──────────┐                      │
        │                   │           │ TfL API  │                      │
        │                   │           └──────────┘                      │
        │                   │                                              │
        │                   │  ┌─────────────────────────────────────┐    │
        │ rate limit check  │  │         RateLimiter                 │    │
        └───────────────────┼─►│         (not an actor)              │    │
                            │  │                                     │    │
                            │  │  ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐  │    │
                            │  │    buckets: Map<IP, TokenBucket>    │    │
                            │  │  │   - per-client state         │  │    │
                            │  │   ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─   │    │
                            │  └─────────────────────────────────────┘    │
                            │                                              │
                            └──────────────────────────────────────────────┘
```

---

## Multi-Node View (3 Nodes)

```
       ┌─────────────────────────────────────────────────────────────────────────────┐
       │                              CLUSTER                                        │
       │                                                                             │
       │   NODE 1                    NODE 2                    NODE 3                │
       │  ┌──────────────────┐      ┌──────────────────┐      ┌──────────────────┐  │
       │  │ TubeStatus       │      │ TubeStatus       │      │ TubeStatus       │  │
       │  │ Replicator       │      │ Replicator       │      │ Replicator       │  │
       │  │    (Actor)       │      │    (Actor)       │      │    (Actor)       │  │
       │  └────────┬─────────┘      └────────┬─────────┘      └────────┬─────────┘  │
       │           │                         │                         │            │
       │           ▼                         ▼                         ▼            │
       │  ┌──────────────────┐      ┌──────────────────┐      ┌──────────────────┐  │
       │  │ Pekko Replicator │      │ Pekko Replicator │      │ Pekko Replicator │  │
       │  │    (Actor)       │      │    (Actor)       │      │    (Actor)       │  │
       │  │                  │      │                  │      │                  │  │
       │  │ ╔══════════════╗ │      │ ╔══════════════╗ │      │ ╔══════════════╗ │  │
       │  │ ║ LWW-Register ║ │      │ ║ LWW-Register ║ │      │ ║ LWW-Register ║ │  │
       │  │ ║   (CRDT)     ║ │      │ ║   (CRDT)     ║ │      │ ║   (CRDT)     ║ │  │
       │  │ ╚══════════════╝ │      │ ╚══════════════╝ │      │ ╚══════════════╝ │  │
       │  └────────┬─────────┘      └────────┬─────────┘      └────────┬─────────┘  │
       │           │                         │                         │            │
       │           │         ╔═══════════════════════════╗             │            │
       │           └────────►║    CRDT GOSSIP PROTOCOL   ║◄────────────┘            │
       │                     ║                           ║                          │
       │                     ║  - Delta-based sync       ║                          │
       │                     ║  - crdt gossip: LWW wins  ║                          │
       │                     ║  - crdt merge: latest     ║                          │
       │                     ║    timestamp wins         ║                          │
       │                     ╚═══════════════════════════╝                          │
       │                                                                             │
       └─────────────────────────────────────────────────────────────────────────────┘

                                         │
                                         │ Any node can fetch
                                         ▼
                                   ┌──────────┐
                                   │ TfL API  │
                                   │ (shared  │
                                   │ upstream)│
                                   └──────────┘
```

---

## Data Classification

### Shared Data (CRDT Replicated)

| Data | Type | Merge Strategy | Replicated Via |
|------|------|----------------|----------------|
| TubeStatus | LWW-Register | Latest timestamp wins | Pekko Distributed Data gossip |

**Only one piece of shared state** - the tube status itself.

### Actor-Private Data

| Actor/Component | Private State | Why Private |
|-----------------|---------------|-------------|
| TubeStatusReplicator | `currentStatus` (local copy) | Fast reads without asking Replicator |
| TubeStatusReplicator | `refreshInFlight` flag | Coalescing logic |
| TubeStatusReplicator | `waiters` list | Requests waiting for refresh |
| TflApiClient | CircuitBreaker state | Per-node circuit state |
| RateLimiter | Token buckets per IP | Per-node rate limiting |

**Private state is not replicated** - each node manages its own.

---

## Message Flow: Client Request

```
1. HTTP Request arrives
        │
        ▼
2. RateLimiter.tryAcquire(clientIP)
        │
        ├── DENIED ──► 429 Too Many Requests
        │
        ▼ ALLOWED
3. TubeStatusReplicator ! GetStatus
        │
        ▼
4. Actor checks private currentStatus
        │
        ├── FRESH ──► Reply immediately
        │
        ▼ STALE
5. Actor checks refreshInFlight
        │
        ├── TRUE ──► Add to waiters, reply with stale
        │
        ▼ FALSE
6. Set refreshInFlight = true
   Trigger scatter-gather (peers + TfL)
        │
        ▼
7. On result:
   - Update CRDT (Replicator ! Update)
   - Update private currentStatus
   - Reply to all waiters
   - Set refreshInFlight = false
        │
        ▼
8. CRDT gossips to other nodes automatically
```

---

## Who Does What

| Responsibility | Component | Actor? |
|----------------|-----------|--------|
| Serve HTTP requests | TubeStatusRoutes | No (Pekko HTTP handler) |
| Rate limiting | RateLimiter | No (called from HTTP handler) |
| Manage refresh logic | TubeStatusReplicator | **Yes** |
| Coalesce requests | TubeStatusReplicator | **Yes** (via mailbox) |
| CRDT read/write | Pekko Replicator | **Yes** (built-in) |
| CRDT merge/gossip | Pekko Replicator | **Yes** (built-in) |
| TfL API calls | TflApiClient | No (called from actor) |
| Circuit breaker | CircuitBreaker | No (state in TflApiClient) |
