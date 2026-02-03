# Operations Guide

Operational documentation for running TfL Tube Status Service.

---

## Documents

| Document | Purpose |
|----------|---------|
| [OBSERVABILITY.md](OBSERVABILITY.md) | Metrics, alerting, logging, tracing |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Helm, GitOps, rolling updates |
| [INFRASTRUCTURE.md](INFRASTRUCTURE.md) | Cloud-agnostic setup |
| [SECURITY.md](SECURITY.md) | TLS, network policies, secrets |
| [ACCESS_CONTROL.md](ACCESS_CONTROL.md) | Restricting to IG employees |
| [RUNBOOKS.md](RUNBOOKS.md) | Incident response |

---

## Quick Links

| If you need to... | Go to... |
|-------------------|----------|
| Respond to an alert | [RUNBOOKS.md](RUNBOOKS.md) |
| Deploy a change | [DEPLOYMENT.md](DEPLOYMENT.md) |
| Understand metrics | [OBSERVABILITY.md](OBSERVABILITY.md) |
| Security review | [SECURITY.md](SECURITY.md) |

---

## SLO Summary

| SLI | Target | Error Budget |
|-----|--------|--------------|
| Availability | 99.9% | 43 min/month |
| Latency (p99) | < 2s | - |
| Freshness | 99.9% < 5min | - |

Full details in [../SLO_DEFINITION.md](../SLO_DEFINITION.md).

---

## Architecture Context

```
Internet → (blocked) → Cluster
Corporate VPN → Ingress → Pods (3+) → TfL API
                           ↕ gossip
```

**Cloud-agnostic:** Same container runs on AWS, GCP, Azure, or on-prem.

---

## Load Reality Check

- Peak load: ~500 req/min (traders checking tube status)
- System capacity: 10,000+ req/s
- A single node handles actual load
- We run 3+ nodes for HA and zero-downtime deploys
