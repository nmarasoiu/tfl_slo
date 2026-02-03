# Operations Guide

This folder contains operational documentation for running TfL Tube Status Service in production.

## Documents

| Document | Purpose |
|----------|---------|
| [OBSERVABILITY.md](OBSERVABILITY.md) | Metrics, dashboards, alerting |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Helm charts, GitOps, rolling upgrades |
| [SECURITY.md](SECURITY.md) | TLS, network policies, WAF, secrets |
| [ACCESS_CONTROL.md](ACCESS_CONTROL.md) | AuthN/AuthZ options, Zero Trust, VPN |
| [INFRASTRUCTURE.md](INFRASTRUCTURE.md) | Terraform, cloud-agnostic, on-prem options |
| [RUNBOOKS.md](RUNBOOKS.md) | Incident response procedures |

## Architecture Context

```
                    ┌─────────────────────────────────────────────────────┐
                    │                   Production                         │
                    │                                                      │
   Internet         │    ┌─────────┐      ┌─────────────────────────┐     │
       │            │    │   WAF   │      │     Kubernetes          │     │
       │            │    │         │      │                         │     │
       ▼            │    └────┬────┘      │  ┌─────┐ ┌─────┐ ┌─────┐│     │
  ┌─────────┐       │         │           │  │Pod 1│ │Pod 2│ │Pod 3││     │
  │   CDN   │───────┼────────►│           │  │     │ │     │ │     ││     │
  │(optional)│      │         ▼           │  └──┬──┘ └──┬──┘ └──┬──┘│     │
  └─────────┘       │    ┌─────────┐      │     │       │       │   │     │
                    │    │ Ingress │──────┼─────┴───────┴───────┘   │     │
                    │    │   (LB)  │      │                         │     │
                    │    └─────────┘      │     Pekko Cluster       │     │
                    │                     │     (CRDT gossip)       │     │
                    │                     └───────────┬─────────────┘     │
                    │                                 │                   │
                    │                                 ▼                   │
                    │                          ┌───────────┐              │
                    │                          │  TfL API  │              │
                    │                          │ (external)│              │
                    │                          └───────────┘              │
                    └─────────────────────────────────────────────────────┘
```

## Deployment Environments

| Environment | Purpose | Nodes | Refresh |
|-------------|---------|-------|---------|
| dev | Local development | 1 | 30s |
| staging | Pre-prod testing | 2 | 30s |
| prod | Production | 3+ | 30s |

## Quick Links

- **Alert firing?** → [RUNBOOKS.md](RUNBOOKS.md)
- **Deploying a change?** → [DEPLOYMENT.md#gitops-workflow](DEPLOYMENT.md#gitops-workflow)
- **Adding metrics?** → [OBSERVABILITY.md#adding-metrics](OBSERVABILITY.md#adding-metrics)
- **Security review?** → [SECURITY.md](SECURITY.md)
- **New environment?** → [INFRASTRUCTURE.md](INFRASTRUCTURE.md)

## On-Prem vs Cloud

This service is **cloud-agnostic** by design. The same container image runs:

| Platform | Load Balancer | Storage | Notes |
|----------|---------------|---------|-------|
| AWS EKS | ALB/NLB | N/A (stateless) | Most common cloud |
| GCP GKE | Cloud LB | N/A | Alternative cloud |
| Azure AKS | Azure LB | N/A | Alternative cloud |
| On-prem K8s | MetalLB / HAProxy | N/A | Current IG setup |
| Bare metal | HAProxy / Nginx | N/A | Non-K8s option |

See [INFRASTRUCTURE.md](INFRASTRUCTURE.md) for detailed setup per platform.

## SLO Summary

| SLI | Target | Error Budget |
|-----|--------|--------------|
| Availability | 99.9% | 43 min/month |
| Latency (p99) | < 2s | - |
| Freshness | 99.9% < 5min | - |

Full details in [../SLO_DEFINITION.md](../SLO_DEFINITION.md).

## Load Expectations

**Reality check:** This architecture is designed to demonstrate SRE patterns at scale, but actual load is modest:

- IG trader base: ~thousands (including retail)
- Tube status checks: 2-3x/day per person
- Peak load: ~100-500 req/min during morning rush
- System capacity: 10,000+ req/s

A single node could handle the actual load. We run 3+ nodes for:
1. **High availability** (survive node failures)
2. **Zero-downtime deployments** (rolling updates)
3. **Demonstrating distributed patterns** (CRDT, cluster)

This is honest SRE: right-size for actual needs, but architect for growth.
