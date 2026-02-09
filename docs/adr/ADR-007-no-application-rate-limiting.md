# ADR-007: No Application-Level Rate Limiting

## Status
Accepted

## Context
The service previously had a per-IP token bucket rate limiter (100 req/min per client) in `RateLimiter.java`. This was applied to all API routes via `TubeStatusRoutes`.

Two concerns emerged:

1. **Near-zero cost responses:** All responses are served from an in-memory CRDT cache. There is no expensive downstream call per request — the TfL API is only called by a background poller (~2 calls/min for the entire cluster). Rate limiting protects nothing expensive.

2. **Throughput target:** The platform targets 10k+ req/s. The rate limiter's `ConcurrentHashMap` of token buckets with CAS loops adds per-request contention that works against this target.

3. **Memory risk:** The `ConcurrentHashMap<String, TokenBucket>` grew unboundedly — one entry per unique client IP with no eviction (the `cleanup()` method existed but was never called). Under diverse traffic or DDoS from many IPs, this is an OOM vector.

## Decision
Remove application-level rate limiting entirely. Delegate DDoS protection to the infrastructure layer (e.g., Cloudflare, AWS WAF, or Kubernetes network policies).

## Consequences

### Positive
- Removes per-request overhead (map lookup + CAS loop) at high throughput
- Eliminates unbounded memory growth from token bucket map
- Simplifies codebase (fewer components to test and maintain)
- Better aligns with cache-first architecture where responses are essentially free

### Negative
- Without an infrastructure-level WAF/CDN, the service is exposed to volumetric DDoS
- A single client can consume disproportionate network/CPU for serialization (though this is bounded by Pekko HTTP's connection limits)

### Mitigation
- Deploy behind Cloudflare or equivalent CDN/WAF for DDoS protection
- Pekko HTTP's built-in connection limits and backpressure provide natural throttling
- The freshness floor (minimum 5s `maxAgeMs`) already prevents clients from forcing excessive TfL API calls

## Alternatives Considered

| Alternative | Verdict |
|------------|---------|
| Keep rate limiter, fix cleanup | Adds overhead for near-zero benefit on cached responses |
| Raise limit to 10k/min per IP | Still adds per-request overhead and memory growth |
| Move to infrastructure-level only | **Chosen** — right layer for DDoS protection |
