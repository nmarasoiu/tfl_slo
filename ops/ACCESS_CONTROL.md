# Access Control

How to restrict access to IG employees only, without exposing the service to the public internet.

---

## Principle: No Public Exposure

**If at all possible, this cluster should have:**
- No public IP address
- No public DNS name
- No ports open to the internet
- No ingress from outside the corporate network

The service handles public TfL data, but we don't want to be a public CDN. We pay for compute, egress, and API quota - only IG should benefit.

```
GOAL:
┌─────────────────────────────────────────────────────────────┐
│                      INTERNET                                │
│                                                              │
│                    ╳ NO ACCESS ╳                             │
│                                                              │
└─────────────────────────────────────────────────────────────┘
                           │
                    (nothing gets through)
                           │
┌─────────────────────────────────────────────────────────────┐
│                    CORPORATE NETWORK                         │
│                                                              │
│    ┌──────────┐         ┌──────────────────────────┐        │
│    │  Trader  │────────►│  tfl-status.internal     │        │
│    │  Device  │         │  (no public IP)          │        │
│    └──────────┘         └──────────────────────────┘        │
│         │                                                    │
│    (on VPN or                                               │
│     office network)                                          │
└─────────────────────────────────────────────────────────────┘
```

---

## Options Ranked by Preference

### 1. Internal Network Only (Recommended)

**How it works:** Service is only reachable from corporate network. No public exposure at all.

```
Trader (on VPN) ──► Corporate Network ──► tfl-status.internal
         │
    Already connected
    for other systems
```

**Implementation:**

```yaml
# Kubernetes: ClusterIP only (no LoadBalancer)
apiVersion: v1
kind: Service
metadata:
  name: tfl-status
spec:
  type: ClusterIP  # Internal only
  ports:
    - port: 8080

# Access via VPN + kubectl port-forward, or internal ingress
```

```yaml
# AWS: Internal ALB (not internet-facing)
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: tfl-status
  annotations:
    alb.ingress.kubernetes.io/scheme: internal
```

```yaml
# On-prem: Internal DNS only
# tfl-status.internal.ig.com -> internal IP
# Not resolvable from public DNS
```

| Pros | Cons |
|------|------|
| Zero public exposure | Requires VPN for remote access |
| Zero code changes | Depends on existing VPN infra |
| Uses existing access patterns | |
| Simplest to implement | |

**Verdict:** ✅ Do this if traders already use VPN for other internal systems.

---

### 2. Cloudflare Access (If Remote Access Needed)

**How it works:** Cloudflare proxy sits in front. Users authenticate via SSO. Service still has no public IP.

```
Trader ──► Cloudflare Edge ──► Cloudflare Tunnel ──► tfl-status
    │            │                    │
    │      (authenticates        (outbound only,
    │       via Okta/Azure AD)    no inbound ports)
    │
   Browser login,
   no client needed
```

**Implementation:**

```yaml
# Cloudflare Tunnel (runs inside cluster, connects outbound)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cloudflared
spec:
  template:
    spec:
      containers:
        - name: cloudflared
          image: cloudflare/cloudflared
          args:
            - tunnel
            - --no-autoupdate
            - run
            - --token
            - $(TUNNEL_TOKEN)
```

```yaml
# Cloudflare Access Policy
application:
  name: tfl-status
  domain: tfl-status.ig.com

access_policy:
  - name: IG Employees
    decision: allow
    include:
      - email_domain: ig.com
    require:
      - login_method: okta  # Or Azure AD, Google, etc.
```

| Pros | Cons |
|------|------|
| No public IP on cluster | Depends on Cloudflare |
| No client install (browser) | Monthly cost |
| SSO with existing IdP | New infrastructure to manage |
| Device posture checks | |
| Works from anywhere | |

**Verdict:** ✅ Good if VPN is not available or remote-first workforce.

---

### 3. Tailscale (If Already Deployed)

**How it works:** Mesh VPN. Service gets a Tailscale IP only reachable from devices on the tailnet.

```
Trader (Tailscale client) ──► Tailnet ──► tfl-status (100.x.x.x)
         │                                      │
    Must have client                     No public IP
    installed                            Only Tailscale IP
```

**Implementation:**

```yaml
# Tailscale sidecar in pod
apiVersion: apps/v1
kind: Deployment
metadata:
  name: tfl-status
spec:
  template:
    spec:
      containers:
        - name: tfl-status
          # ... app container
        - name: tailscale
          image: tailscale/tailscale
          env:
            - name: TS_AUTHKEY
              valueFrom:
                secretKeyRef:
                  name: tailscale
                  key: authkey
```

```json
// Tailscale ACL
{
  "acls": [
    {
      "action": "accept",
      "src": ["group:ig-traders"],
      "dst": ["tag:tfl-status:*"]
    }
  ]
}
```

| Pros | Cons |
|------|------|
| No public IP at all | Requires client on every device |
| Great security model | Need to roll out if not already using |
| Simple once deployed | Not worth it for one service |
| WireGuard-based (fast) | |

**Verdict:** ⚠️ Only if IG already uses Tailscale org-wide. Don't deploy just for this.

---

### 4. Google Identity-Aware Proxy (GCP Only)

**How it works:** Google's Zero Trust proxy. Similar to Cloudflare Access but GCP-native.

```
Trader ──► Google IAP ──► GKE Service
              │
        Google login
        (if using Google Workspace)
```

| Pros | Cons |
|------|------|
| Native GCP integration | GCP only |
| No client needed | Requires Google Workspace or Cloud Identity |
| Built into GKE | |

**Verdict:** ✅ If on GCP and using Google Workspace.

---

### 5. AWS Client VPN / Azure VPN (Cloud-Native VPN)

**How it works:** Cloud provider's managed VPN. Service on private subnet.

```
Trader ──► AWS Client VPN ──► Private Subnet ──► tfl-status
              │
         AWS SSO or
         existing IdP
```

| Pros | Cons |
|------|------|
| Managed service | Client install required |
| Integrates with cloud IAM | Cost |
| Private networking | Cloud-specific |

**Verdict:** ⚠️ Consider if migrating to cloud and want managed VPN.

---

### 6. mTLS with Client Certificates

**How it works:** Every client needs a certificate. Service validates cert before allowing access.

```
Trader (with client cert) ──► Service (validates cert)
         │
    Cert provisioned via
    corporate PKI
```

| Pros | Cons |
|------|------|
| Strong authentication | Certificate management overhead |
| No passwords | Need PKI infrastructure |
| Works for service-to-service | Poor UX for humans |

**Verdict:** ❌ Over-engineering for browser-based access. Use for service-to-service only.

---

### 7. API Keys

**How it works:** Issue API keys, validate on each request.

```
Trader ──► Service ──► Validate API key ──► Allow/Deny
              │
         Key in header
         or query param
```

| Pros | Cons |
|------|------|
| Simple to implement | Key management |
| Works for programmatic access | Keys can leak |
| | No SSO integration |
| | Need to build revocation |

**Verdict:** ❌ Don't build this. Use a proxy that handles auth.

---

### 8. OAuth2/OIDC in Application

**How it works:** Add authentication to the application itself.

```
Trader ──► tfl-status ──► Redirect to IdP ──► Login ──► Callback ──► Access
```

| Pros | Cons |
|------|------|
| User identity in app | Significant code changes |
| Fine-grained authz possible | Session management complexity |
| | Every service needs this |
| | Just use a proxy instead |

**Verdict:** ❌ Over-engineering. We don't need user identity for tube status.

---

## Decision Matrix

| Option | Public Exposure | Client Required | Code Changes | Effort | When to Use |
|--------|-----------------|-----------------|--------------|--------|-------------|
| **Internal network** | None | VPN (existing) | None | Low | Default choice |
| **Cloudflare Access** | None (tunnel) | None (browser) | None | Medium | Remote access needed |
| **Tailscale** | None | Yes (new) | Minimal | Medium | Already using Tailscale |
| **Google IAP** | None | None | None | Low | On GCP |
| **Cloud VPN** | None | Yes | None | Medium | Cloud-native preference |
| mTLS | Public IP needed | Cert | Minimal | High | Service-to-service |
| API Keys | Public IP needed | None | Yes | Medium | Never for this |
| OAuth2 in app | Public IP needed | None | High | High | Never for this |

---

## Recommendation for IG

### Given: IG is on-prem, traders already use VPN

```
┌─────────────────────────────────────────────────────────────┐
│                                                              │
│   1. DEPLOY ON INTERNAL NETWORK                             │
│      └─ ClusterIP service, no LoadBalancer                  │
│      └─ Internal DNS: tfl-status.internal.ig.com            │
│      └─ Traders access via existing VPN                     │
│      └─ Zero new infrastructure                             │
│                                                              │
│   2. IF REMOTE ACCESS BECOMES CRITICAL                      │
│      └─ Add Cloudflare Access with tunnel                   │
│      └─ Still no public IP on cluster                       │
│      └─ SSO via existing Okta/Azure AD                      │
│                                                              │
│   3. DON'T                                                  │
│      └─ Roll out Tailscale just for this                    │
│      └─ Add OAuth2 to the application                       │
│      └─ Expose to public internet with API keys             │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### The Golden Rule

> **"Use whatever traders already use to access internal systems."**

If that's VPN → internal network
If that's Cloudflare Access → add this service to the same setup
If that's office network → service on internal LB

Don't make traders do something new for tube status. It's not important enough to justify friction.

---

## Implementation Checklist

### For Internal Network Only

- [ ] Service type: ClusterIP (not LoadBalancer)
- [ ] Ingress: internal class or none
- [ ] DNS: internal zone only (not public)
- [ ] Firewall: no inbound from internet
- [ ] Documentation: "Access via VPN"

### For Cloudflare Access (if needed later)

- [ ] Deploy cloudflared tunnel in cluster
- [ ] Create Access application in Cloudflare dashboard
- [ ] Configure IdP integration (Okta/Azure AD)
- [ ] Set access policy (email domain, device posture)
- [ ] Test with pilot users
- [ ] No changes to tfl-status application
