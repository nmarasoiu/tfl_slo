# Runbooks

Incident response procedures for TfL Tube Status Service.

---

## Quick Reference

| Alert | Severity | First Action |
|-------|----------|--------------|
| TflAvailabilityCriticalBurn | PAGE | Check pods → Check circuit breaker → Rollback if needed |
| TflLatencyCriticalBurn | PAGE | Check if TfL API is slow → Scale out if CPU-bound |
| TflDataStale | TICKET | Check circuit breaker → Verify TfL API reachable |
| TflCircuitBreakerOpen | TICKET | Check TfL status page → Wait for recovery |
| TflClusterMemberDown | TICKET | Check pod/node health → May auto-recover |

---

## R1: Availability Critical

**Alert:** `TflAvailabilityCriticalBurn`
**Meaning:** Error rate exceeds 14.4x burn rate (exhausts 2-day budget)

### Diagnosis

```bash
kubectl -n tfl-status get pods
kubectl -n tfl-status logs -l app=tfl-status --since=5m | grep -i error
curl -s http://<pod>:8080/api/health/ready | jq .
```

### Common Causes

| Cause | Indicators | Action |
|-------|------------|--------|
| Pod crash loop | `CrashLoopBackOff` | Check logs, rollback |
| OOM kills | `OOMKilled` in events | Increase memory limits |
| TfL API down | Circuit breaker OPEN | Wait (service serves stale) |
| Bad deployment | Errors after deploy | `kubectl rollout undo deployment/tfl-status` |

---

## R2: Circuit Breaker Open

**Alert:** `TflCircuitBreakerOpen`
**Meaning:** TfL API has failed 5+ consecutive times

### Check TfL Directly

```bash
curl -s "https://api.tfl.gov.uk/Line/central/Status" | head -c 200
# Also check: https://tfl.gov.uk/tube-dlr-overground/status/
```

### Behavior When Open

- Service continues serving cached/stale data
- Responses include `X-Data-Stale: true` header
- Circuit auto-recovers after 30s (half-open probe)

**No action required if:** TfL status page shows issues, users aren't complaining.

---

## R3: Cluster Member Down

**Alert:** `TflClusterMemberDown`
**Meaning:** Fewer than 3 healthy nodes

### Diagnosis

```bash
kubectl -n tfl-status get pods -o wide
kubectl -n tfl-status get events --sort-by='.lastTimestamp' | tail -10
kubectl -n tfl-status exec -it <pod> -- curl localhost:8558/cluster/members
```

### Cluster Partition Behavior

- Service remains available (AP design)
- Both partitions serve data independently
- On recovery, CRDT merges automatically (LWW)
- No data loss, just brief inconsistency

---

## General Commands

```bash
# Logs
kubectl -n tfl-status logs -l app=tfl-status -f
kubectl -n tfl-status logs <pod> --previous

# Debug
kubectl -n tfl-status exec -it <pod> -- /bin/sh
kubectl -n tfl-status port-forward svc/tfl-status 8080:8080

# Rollback
kubectl -n tfl-status rollout undo deployment/tfl-status
```

---

## Contacts

| Role | Contact | When |
|------|---------|------|
| Primary On-Call | PagerDuty rotation | Alerts |
| TfL API Issues | https://tfl.gov.uk/status | TfL-side problems |
| Platform Team | #platform-support | K8s/infra issues |
