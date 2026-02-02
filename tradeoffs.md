 Major Architectural Tradeoffs

  1. Freshness vs Availability (the fundamental tension)
  ┌────────────────────────────┬────────────────┬────────────────────┬────────────┐
  │          Approach          │   Freshness    │    Availability    │ Complexity │
  ├────────────────────────────┼────────────────┼────────────────────┼────────────┤
  │ Always hit TfL API         │ Real-time      │ Tied to TfL uptime │ Low        │
  ├────────────────────────────┼────────────────┼────────────────────┼────────────┤
  │ Cache with TTL             │ Stale by TTL   │ High (serve stale) │ Medium     │
  ├────────────────────────────┼────────────────┼────────────────────┼────────────┤
  │ Cache + background refresh │ Near real-time │ High               │ Higher     │
  └────────────────────────────┴────────────────┴────────────────────┴────────────┘
  Decision point: When circuit is open, do you return an error or serve stale cached data? Trading platform context suggests stale data > no data (traders can see "as of 2 min ago").

  2. Circuit Breaker: Fail Fast vs Graceful Degradation

  - Fail fast: Circuit open → immediate 503 → client knows to back off
  - Graceful: Circuit open → serve cached/default response → client unaware of upstream issues

  The spec says "return appropriate error response" but interview discussion asks "what happens when TfL is down for 6 hours?" - they want to hear you'd serve cached.

  3. Retry: Latency vs Success Rate

  More retries    → Higher eventual success → Longer tail latency
  Fewer retries   → Faster failure          → Lower success rate

  With exponential backoff: 1s + 2s + 4s = 7s worst case for 3 retries. Is 7s acceptable for a trading platform? Probably - this is commute info, not market data.

  4. Rate Limiting: Where to Enforce
  ┌────────────────────────┬───────────────────────────┬──────────────────────────────────┐
  │        Location        │         Protects          │             Drawback             │
  ├────────────────────────┼───────────────────────────┼──────────────────────────────────┤
  │ Per-IP at your service │ Your service from clients │ Doesn't protect TfL              │
  ├────────────────────────┼───────────────────────────┼──────────────────────────────────┤
  │ Global outbound to TfL │ TfL from you              │ All your clients share the limit │
  ├────────────────────────┼───────────────────────────┼──────────────────────────────────┤
  │ Both                   │ Everything                │ More complex                     │
  └────────────────────────┴───────────────────────────┴──────────────────────────────────┘
  They specify per-IP inbound, but you should also respect TfL's limits outbound.

  5. SLO Target: Aggressive vs Achievable

  Your SLO cannot exceed TfL's reliability. If TfL is ~99.5% available, claiming 99.99% is fantasy unless you have caching/fallback.

  Your SLO ≤ min(TfL reliability, your service reliability)
           unless you have caching that decouples them

  6. Percentile Choice for Latency SLI (your DDSketch point)
  ┌────────────┬───────────────────┬───────────────────────────────────────────┐
  │ Percentile │  What it catches  │                Noise level                │
  ├────────────┼───────────────────┼───────────────────────────────────────────┤
  │ p50        │ Median experience │ Very stable                               │
  ├────────────┼───────────────────┼───────────────────────────────────────────┤
  │ p95        │ Most users        │ Stable                                    │
  ├────────────┼───────────────────┼───────────────────────────────────────────┤
  │ p99        │ Tail latency      │ Some noise                                │
  ├────────────┼───────────────────┼───────────────────────────────────────────┤
  │ p99.9      │ Extreme tail      │ Noisy, needs DDSketch-quality measurement │
  └────────────┴───────────────────┴───────────────────────────────────────────┘
  For SLOs, p99 is typical. p99.9 is ambitious and requires accurate measurement (as you know from the ZFS histograms - coarse buckets lose tail precision).

  7. Java Framework Choice
  ┌────────────────────────────────────┬───────────────────────────────┬───────────┐
  │               Option               │      Resilience Support       │ Overhead  │
  ├────────────────────────────────────┼───────────────────────────────┼───────────┤
  │ Spring Boot + Resilience4j         │ Built-in annotations          │ Heavier   │
  ├────────────────────────────────────┼───────────────────────────────┼───────────┤
  │ Micronaut/Quarkus + Resilience4j   │ Same patterns, faster startup │ Medium    │
  ├────────────────────────────────────┼───────────────────────────────┼───────────┤
  │ Plain Java + manual implementation │ Shows understanding           │ More code │
  └────────────────────────────────────┴───────────────────────────────┴───────────┘
  Spring Boot + Resilience4j is the pragmatic 3-hour choice. Shows you know production tooling.

