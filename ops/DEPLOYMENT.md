# Deployment

Helm charts, GitOps workflow, and deployment strategies.

---

## Summary

| Aspect | Approach |
|--------|----------|
| Packaging | Helm chart |
| Deployment | GitOps (ArgoCD) - cluster pulls from Git |
| Strategy | Rolling update with PDB |
| Rollback | ArgoCD history or `kubectl rollout undo` |

---

## Helm Chart Design

```
helm/tfl-status/
├── Chart.yaml
├── values.yaml           # Defaults
├── values-staging.yaml   # 2 replicas, smaller resources
├── values-prod.yaml      # 3+ replicas, HPA enabled
└── templates/
    ├── deployment.yaml
    ├── service.yaml
    ├── pdb.yaml          # Pod Disruption Budget
    └── servicemonitor.yaml
```

### Key Values

| Parameter | Staging | Prod |
|-----------|---------|------|
| `replicaCount` | 2 | 3 |
| `resources.requests.memory` | 128Mi | 256Mi |
| `pdb.minAvailable` | 1 | 2 |
| `autoscaling.enabled` | false | true |

---

## GitOps Flow

```
Developer → Push code → CI builds image → CI updates Git manifest
                                                │
                                                ▼
                              ArgoCD (in cluster) detects change
                                                │
                                        ┌───────┴───────┐
                                        ▼               ▼
                                    Staging          Production
                                  (auto-sync)      (manual approval)
```

**Key principle:** CI never touches the cluster. ArgoCD pulls from Git.

---

## Rolling Update Sequence

With `maxUnavailable: 1` and `maxSurge: 1`:

```
[v1, v1, v1] → [v1, v1, v1, v2] → [v1, v1, v2] → ... → [v2, v2, v2]
```

- New pod starts (surge)
- New pod becomes ready
- Old pod terminates
- Repeat until done

**Pod Disruption Budget** ensures ≥2 pods available at all times in prod.

---

## Graceful Shutdown

```java
// Already implemented in TflApplication.java
Runtime.getRuntime().addShutdownHook(() -> {
    serverBinding.unbind();  // Stop accepting requests
    system.terminate();      // Coordinated shutdown
});
```

Combined with:
- `terminationGracePeriodSeconds: 30`
- `preStop` hook with `sleep 5` to let LB drain

---

## Rollback

| Method | Command |
|--------|---------|
| ArgoCD | `argocd app rollback tfl-status` |
| kubectl | `kubectl rollout undo deployment/tfl-status` |
| Helm | `helm rollback tfl-status 1` |

**Auto-halt:** Kubernetes stops rollout if new pods fail readiness probes.

---

## Environment Promotion

```
main → Build → Image ghcr.io/ig/tfl-status:abc123
                │
                ├──► Staging (auto-deploy, E2E tests)
                │           │
                │           ▼ Tests pass
                └──► Prod (PR review → merge → ArgoCD syncs)
```

---

*Full Helm templates, ArgoCD Application YAML, and GitHub Actions CI available on request.*
