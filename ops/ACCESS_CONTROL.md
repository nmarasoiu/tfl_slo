# Access Control

How to restrict access to IG employees only.

---

## Principle: No Public Exposure

**This cluster should have:**
- No public IP address
- No public DNS name
- No ports open to the internet

The service handles public TfL data, but we don't want to be a public CDN. Only IG should benefit.

```
GOAL:
┌────────────────────────────────────────────┐
│              INTERNET                       │
│            ╳ NO ACCESS ╳                   │
└────────────────────────────────────────────┘
                    │
             (nothing gets through)
                    │
┌────────────────────────────────────────────┐
│          CORPORATE NETWORK                  │
│    Trader ──► tfl-status.internal          │
│  (on VPN)     (no public IP)               │
└────────────────────────────────────────────┘
```

---

## Decision Matrix

| Option | Public Exposure | Client Required | Code Changes | Recommendation |
|--------|-----------------|-----------------|--------------|----------------|
| **Internal network** | None | VPN (existing) | None | ✅ Default choice |
| **Cloudflare Access** | None (tunnel) | None (browser) | None | ✅ For remote access |
| **Tailscale** | None | Yes | Minimal | ⚠️ Only if already using |
| **Google IAP** | None | None | None | ✅ If on GCP |
| mTLS | Public IP | Cert | Minimal | ❌ Over-engineering |
| API Keys | Public IP | None | Yes | ❌ Don't build this |
| OAuth2 in app | Public IP | None | High | ❌ Use proxy instead |

---

## Recommendation for IG

**Given:** IG is on-prem, traders already use VPN.

1. **Deploy on internal network** (default)
   - ClusterIP service, no LoadBalancer
   - Internal DNS: `tfl-status.internal.ig.com`
   - Traders access via existing VPN

2. **If remote access becomes critical**
   - Add Cloudflare Access with tunnel (outbound only, no public IP)
   - SSO via existing Okta/Azure AD

3. **Don't**
   - Roll out Tailscale just for this
   - Add OAuth2 to the application
   - Expose to public internet with API keys

---

## The Golden Rule

> **"Use whatever traders already use to access internal systems."**

If that's VPN → internal network
If that's Cloudflare Access → add this service to same setup
If that's office network → service on internal LB

Don't make traders do something new for tube status.

---

## Implementation Checklist

**For Internal Network Only:**
- [ ] Service type: ClusterIP (not LoadBalancer)
- [ ] Ingress: internal class or none
- [ ] DNS: internal zone only
- [ ] Firewall: no inbound from internet
