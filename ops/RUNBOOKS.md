# Runbooks

Incident response procedures for TfL Tube Status Service.

---

## Quick Reference

| Alert | Severity | First Action |
|-------|----------|--------------|
| TflAvailabilityCriticalBurn | PAGE | Check service health → [R1](#r1-availability-critical) |
| TflLatencyCriticalBurn | PAGE | Check TfL API latency → [R2](#r2-latency-critical) |
| TflDataStale | TICKET | Check circuit breaker → [R3](#r3-data-stale) |
| TflCircuitBreakerOpen | TICKET | Check TfL status → [R4](#r4-circuit-breaker-open) |
| TflClusterMemberDown | TICKET | Check node health → [R5](#r5-cluster-member-down) |

---

## R1: Availability Critical {#r1-availability-critical}

**Alert:** `TflAvailabilityCriticalBurn`
**Severity:** PAGE
**Meaning:** Error rate exceeds 14.4x burn rate (exhausts 2-day budget)

### Diagnosis

```bash
# 1. Check pod status
kubectl -n tfl-status get pods

# 2. Check recent errors
kubectl -n tfl-status logs -l app=tfl-status --since=5m | grep -i error

# 3. Check circuit breaker state
curl -s http://<any-pod>:8080/api/health/ready | jq .

# 4. Check metrics
# - http_requests_total{status=~"5.."}
# - circuit_breaker_state
```

### Common Causes & Remediation

| Cause | Indicators | Action |
|-------|------------|--------|
| Pod crash loop | `CrashLoopBackOff` status | Check logs, fix code/config, rollback if needed |
| OOM kills | `OOMKilled` in events | Increase memory limits |
| TfL API down | Circuit breaker OPEN | Wait for TfL recovery (see [R4](#r4-circuit-breaker-open)) |
| Network issue | Connection timeouts | Check network policies, DNS |
| Bad deployment | Errors started after deploy | Rollback: `kubectl rollout undo deployment/tfl-status` |

### Escalation

If not resolved in 15 minutes:
1. Page secondary on-call
2. Consider rollback even if cause unknown
3. Open incident channel

---

## R2: Latency Critical {#r2-latency-critical}

**Alert:** `TflLatencyCriticalBurn`
**Severity:** PAGE
**Meaning:** p99 latency exceeds 2 seconds

### Diagnosis

```bash
# 1. Check where latency is coming from
# Dashboard: tfl-status-overview → Latency panel

# 2. Check TfL API latency specifically
kubectl -n tfl-status logs -l app=tfl-status --since=5m | grep "TfL fetch"

# 3. Check if it's cache misses
curl -s "http://<pod>:8080/api/v1/tube/status" -w "\nTime: %{time_total}s\n"

# 4. Check node resources
kubectl top pods -n tfl-status
```

### Common Causes & Remediation

| Cause | Indicators | Action |
|-------|------------|--------|
| TfL API slow | TfL request logs show >1s | Nothing to do, TfL's problem |
| Cache cold | Many TfL fetches in logs | Wait for cache warm-up |
| CPU throttling | High CPU usage | Increase CPU limits or scale out |
| GC pressure | GC pause logs | Tune JVM, increase memory |
| Network latency | Slow DNS, pod-to-pod | Check network, CoreDNS |

### Mitigation

```bash
# If TfL is slow, we can only wait. Service will serve stale data.
# If CPU bound, scale out:
kubectl -n tfl-status scale deployment/tfl-status --replicas=5
```

---

## R3: Data Stale {#r3-data-stale}

**Alert:** `TflDataStale`
**Severity:** TICKET
**Meaning:** Average data freshness > 5 minutes

### Diagnosis

```bash
# 1. Check current freshness
curl -s http://<pod>:8080/api/v1/tube/status | jq '.meta.ageMs'

# 2. Check circuit breaker
curl -s http://<pod>:8080/api/health/ready | jq '.circuit'

# 3. Check if TfL fetches are happening
kubectl -n tfl-status logs -l app=tfl-status --since=10m | grep -E "(TfL fetch|Got fresh data)"

# 4. Check CRDT replication
kubectl -n tfl-status logs -l app=tfl-status --since=10m | grep -i "crdt\|replicat"
```

### Common Causes & Remediation

| Cause | Indicators | Action |
|-------|------------|--------|
| Circuit breaker OPEN | `circuit: "OPEN"` | See [R4](#r4-circuit-breaker-open) |
| TfL API returning errors | 5xx in logs | Wait for TfL recovery |
| All nodes failing to fetch | No "Got fresh data" logs | Check network egress to TfL |
| CRDT not replicating | Only one node has fresh data | Check cluster membership |

### Mitigation

```bash
# Force a manual refresh (if one node can reach TfL)
# Connect to a pod and trigger refresh by requesting with maxAgeMs=0
curl "http://<pod>:8080/api/v1/tube/status?maxAgeMs=0"
```

---

## R4: Circuit Breaker Open {#r4-circuit-breaker-open}

**Alert:** `TflCircuitBreakerOpen`
**Severity:** TICKET
**Meaning:** TfL API has failed 5+ consecutive times

### Diagnosis

```bash
# 1. Check TfL API directly
curl -s "https://api.tfl.gov.uk/Line/central/Status" | head -c 200

# 2. Check TfL status page
# https://tfl.gov.uk/tube-dlr-overground/status/

# 3. Check our error logs
kubectl -n tfl-status logs -l app=tfl-status --since=10m | grep -i "circuit\|tfl"

# 4. Check network egress
kubectl -n tfl-status exec -it <pod> -- curl -v https://api.tfl.gov.uk/Line/central/Status
```

### Common Causes & Remediation

| Cause | Indicators | Action |
|-------|------------|--------|
| TfL API down | Direct curl fails | Wait for TfL recovery |
| TfL rate limiting us | 429 responses | Reduce refresh rate (shouldn't happen) |
| Network policy blocking | Connection refused | Check egress policies |
| DNS resolution failure | Name resolution errors | Check CoreDNS |
| TLS certificate issue | SSL errors | Check system CA certs |

### Behavior When Open

- Service continues to serve cached/stale data
- Responses include `confidence: DEGRADED` (if implemented)
- Circuit will auto-recover after 30s (half-open probe)

### No Action Required If

- TfL status page shows known issues
- Service is still serving (stale) data
- Users are not complaining

---

## R5: Cluster Member Down {#r5-cluster-member-down}

**Alert:** `TflClusterMemberDown`
**Severity:** TICKET
**Meaning:** Fewer than 3 healthy nodes in cluster

### Diagnosis

```bash
# 1. Check pod status
kubectl -n tfl-status get pods -o wide

# 2. Check node status
kubectl get nodes

# 3. Check events
kubectl -n tfl-status get events --sort-by='.lastTimestamp' | tail -20

# 4. Check Pekko cluster status (from a healthy pod)
kubectl -n tfl-status exec -it <healthy-pod> -- curl localhost:8558/cluster/members
```

### Common Causes & Remediation

| Cause | Indicators | Action |
|-------|------------|--------|
| Pod crashed | `Error` or `CrashLoopBackOff` | Check logs, may auto-recover |
| Node failure | Node `NotReady` | Check node, may need replacement |
| Deployment in progress | Rolling update | Wait for completion |
| Resource exhaustion | `Evicted` status | Check node resources |
| Network partition | Pod running but unreachable | Check network policies |

### Recovery

```bash
# If pod won't start, check why
kubectl -n tfl-status describe pod <pod-name>
kubectl -n tfl-status logs <pod-name> --previous

# If node is bad, cordon and drain
kubectl cordon <node-name>
kubectl drain <node-name> --ignore-daemonsets

# Force delete stuck pod (last resort)
kubectl -n tfl-status delete pod <pod-name> --force --grace-period=0
```

### Cluster Partition

If cluster is split-brain:
1. Service remains available (AP design)
2. Both partitions serve data independently
3. On recovery, CRDT merges automatically (LWW)
4. No data loss, just potential brief inconsistency

---

## R6: High Rate Limit Rejections {#r6-rate-limit}

**Alert:** `TflHighRateLimitRejections`
**Severity:** INFO
**Meaning:** >1% of requests being rate-limited

### Diagnosis

```bash
# 1. Check which IPs are being limited
kubectl -n tfl-status logs -l app=tfl-status --since=5m | grep "429\|rate.limit"

# 2. Check request patterns
# Dashboard: tfl-status-overview → Traffic panel

# 3. Check if it's one client or many
# Group by client IP in logs
```

### Common Causes & Remediation

| Cause | Indicators | Action |
|-------|------------|--------|
| Misbehaving client | One IP in logs | Contact client owner, or block |
| Load test running | Sudden spike, known source | Expected, ignore |
| DDoS attempt | Many IPs, unusual patterns | Escalate to security |
| Legitimate traffic growth | Gradual increase | Consider raising limits |

### Adjusting Rate Limits

```java
// In TubeStatusRoutes.java
this.rateLimiter = RateLimiter.perMinute(200);  // Increase from 100

// Or in ingress
nginx.ingress.kubernetes.io/limit-rps: "20"  // Increase
```

---

## R7: Deployment Failure {#r7-deployment-failure}

**Symptoms:** New pods not becoming ready, rollout stuck

### Diagnosis

```bash
# 1. Check rollout status
kubectl -n tfl-status rollout status deployment/tfl-status

# 2. Check new pod status
kubectl -n tfl-status get pods
kubectl -n tfl-status describe pod <new-pod>

# 3. Check readiness probe
kubectl -n tfl-status logs <new-pod> | head -50
curl http://<new-pod-ip>:8080/api/health/ready
```

### Common Causes & Remediation

| Cause | Indicators | Action |
|-------|------------|--------|
| Image pull failure | `ImagePullBackOff` | Check image tag, registry auth |
| Config error | App crashes on start | Check logs, fix config, rollback |
| Readiness probe failing | Pod never Ready | Check probe path, app startup |
| Resource limits too low | OOMKilled | Increase limits |
| Cluster can't form | Waiting for seed nodes | Check PEKKO_SEED_NODES config |

### Rollback

```bash
# Immediate rollback
kubectl -n tfl-status rollout undo deployment/tfl-status

# Rollback to specific revision
kubectl -n tfl-status rollout history deployment/tfl-status
kubectl -n tfl-status rollout undo deployment/tfl-status --to-revision=3
```

---

## General Troubleshooting Commands

```bash
# Pod logs (current)
kubectl -n tfl-status logs -l app=tfl-status -f

# Pod logs (previous crash)
kubectl -n tfl-status logs <pod> --previous

# Exec into pod
kubectl -n tfl-status exec -it <pod> -- /bin/sh

# Port forward for local testing
kubectl -n tfl-status port-forward svc/tfl-status 8080:8080

# Check all resources
kubectl -n tfl-status get all

# Describe for events
kubectl -n tfl-status describe deployment/tfl-status

# Resource usage
kubectl -n tfl-status top pods

# Network debug pod
kubectl run -it --rm debug --image=curlimages/curl -- sh
```

---

## Incident Template

```markdown
## Incident: [Title]

**Severity:** P1/P2/P3
**Started:** YYYY-MM-DD HH:MM UTC
**Resolved:** YYYY-MM-DD HH:MM UTC
**Duration:** X minutes

### Summary
One-line description of what happened.

### Impact
- X% of requests failed
- Y users affected
- Z minutes of degraded service

### Timeline
- HH:MM - Alert fired
- HH:MM - On-call acknowledged
- HH:MM - Root cause identified
- HH:MM - Mitigation applied
- HH:MM - Resolved

### Root Cause
Description of what caused the incident.

### Resolution
What was done to fix it.

### Action Items
- [ ] Item 1 (owner, due date)
- [ ] Item 2 (owner, due date)

### Lessons Learned
What we learned and how to prevent recurrence.
```

---

## Contacts

| Role | Contact | When |
|------|---------|------|
| Primary On-Call | PagerDuty rotation | Alerts |
| Secondary On-Call | PagerDuty escalation | 15 min no response |
| TfL API Issues | https://tfl.gov.uk/status | TfL-side problems |
| Platform Team | #platform-support | K8s/infra issues |
| Security | #security-incidents | Security concerns |
