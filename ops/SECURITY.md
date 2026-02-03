# Security

TLS, network policies, WAF, and secrets management.

---

## Summary

**Honest assessment:** This service has almost nothing to protect.

| Secret | Exists? | Notes |
|--------|---------|-------|
| TfL API key | **No** | TfL API is public, no auth needed |
| Database credentials | **No** | No database (stateless cache) |
| User data | **No** | No user accounts, no PII |
| Inter-node TLS certs | Optional | Only if enabling Pekko TLS |
| Ingress TLS certs | Yes | Managed by cert-manager (auto-rotated) |

**Total secrets to manage: ~0-2** (just TLS certs if we enable inter-node encryption)

---

## TLS Strategy

| Connection | Current | Production | Implementation |
|------------|---------|------------|----------------|
| Client → Ingress | HTTP | HTTPS | cert-manager + Let's Encrypt |
| Ingress → Service | HTTP | HTTP (internal) | K8s network policy |
| Service → TfL API | HTTPS | HTTPS | Already implemented |
| Node ↔ Node (Pekko) | Unencrypted | TLS | Pekko Artery SSL |

**Standards:** TLS 1.2 minimum, TLS 1.3 preferred. Strong ciphers only.

---

## Network Security

**Principle:** Default deny, explicit allow.

| Traffic | Allow |
|---------|-------|
| Ingress → Service | Port 8080 only |
| Service → TfL API | HTTPS (443) outbound |
| Service ↔ Service (cluster) | Pekko ports (2551, 8558) |
| Prometheus → Service | Port 8080 (metrics scrape) |

---

## Application-Level Protection (Implemented)

| Protection | Implementation |
|------------|----------------|
| Rate limiting | `RateLimiter.java` (100 req/min per IP) |
| Input validation | Line ID checked against known values |
| Date validation | Date range validated in routes |
| Request timeout | Pekko HTTP request timeout |

---

## Secrets Management Options

| Approach | Complexity | When to Use |
|----------|------------|-------------|
| Kubernetes Secrets | Low | Dev/staging |
| Sealed Secrets | Low | GitOps-friendly |
| External Secrets Operator | Medium | Cloud secret stores |
| HashiCorp Vault | High | Enterprise, rotation |

**Recommendation for this service:** Sealed Secrets or External Secrets Operator. Minimal secrets, so simple approach is fine.

---

## Container Security Checklist

- [ ] TLS on ingress (cert-manager)
- [ ] Network policies applied (default deny + allow list)
- [ ] Secrets not in git (sealed-secrets or external)
- [ ] Container runs as non-root
- [ ] Read-only root filesystem
- [ ] Resource limits set
- [ ] Image scanning in CI (OWASP, Trivy)
- [ ] Pinned image versions (never `latest`)

---

## Image Versioning

**Always use fixed, immutable tags—never `latest`.**

| Image Reference | Status |
|-----------------|--------|
| `eclipse-temurin:21-jre-alpine` | **Bad** - alpine version floats |
| `eclipse-temurin:21.0.2_13-jre-alpine3.19` | **Good** - fully pinned |
| `ghcr.io/ig/tfl-status:latest` | **Bad** - mutable |
| `ghcr.io/ig/tfl-status:abc123` | **Good** - SHA-based |

---

## GitOps Security: Pull-Based Deployment

**Principle:** Cluster pulls from Git. CI never touches cluster directly.

```
CI → pushes image to registry
CI → updates Git manifest
ArgoCD (in cluster) → pulls from Git → deploys
```

**Benefits:**
- No cluster credentials in CI
- No inbound ports for deployment
- Audit trail in Git

---

## Compliance Summary

| Control | Status |
|---------|--------|
| Encryption in transit | ✓ TLS everywhere |
| Encryption at rest | N/A (no persistence) |
| Access control | ✓ RBAC + network policies |
| Audit logging | ✓ K8s audit + app logs |
| Vulnerability management | ✓ Image scanning |

---

*Full YAML examples for TLS configs, network policies, Vault integration available on request. Omitted here as the service has minimal security requirements.*
