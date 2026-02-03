# Security

TLS, network policies, WAF, and secrets management.

---

## 0. Secrets Inventory (What We Actually Have)

**Honest assessment:** This service has almost nothing to protect.

| Secret | Exists? | Notes |
|--------|---------|-------|
| TfL API key | **No** | TfL API is public, no auth needed |
| Database credentials | **No** | No database (stateless cache) |
| User data | **No** | No user accounts, no PII |
| Inter-node TLS certs | Optional | Only if enabling Pekko TLS |
| Ingress TLS certs | Yes | Managed by cert-manager (auto-rotated) |

**Total secrets to manage: ~0-2** (just TLS certs if we enable inter-node encryption)

### Why Document Security Anyway?

1. **Demonstrate SRE maturity** - Know the patterns even when not strictly needed
2. **Future-proofing** - If we add authenticated endpoints or DB later
3. **Defense in depth** - Protect infrastructure even if app has nothing sensitive
4. **Compliance** - Some environments require security controls regardless

---

## 1. TLS Configuration

### Traffic Encryption Matrix

| Connection | Current | Production | Implementation |
|------------|---------|------------|----------------|
| Client → Ingress | HTTP | HTTPS | cert-manager + Let's Encrypt |
| Ingress → Service | HTTP | HTTP (internal) | K8s network policy |
| Service → TfL API | HTTPS | HTTPS | Already implemented |
| Node ↔ Node (Pekko) | Unencrypted | TLS | Pekko Artery SSL |

### TLS Version and Cipher Configuration

**Minimum:** TLS 1.2 (required)
**Preferred:** TLS 1.3 (when available)

#### NGINX Ingress TLS Config

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: nginx-ingress-controller
  namespace: ingress-nginx
data:
  # TLS versions
  ssl-protocols: "TLSv1.2 TLSv1.3"

  # TLS 1.3 ciphers (preferred)
  ssl-ciphers: "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-RSA-AES128-GCM-SHA256"

  # Prefer server ciphers
  ssl-prefer-server-ciphers: "true"

  # HSTS (1 year, include subdomains)
  hsts: "true"
  hsts-max-age: "31536000"
  hsts-include-subdomains: "true"
```

#### Pekko Cluster TLS Config

```hocon
pekko.remote.artery.ssl.config-ssl-engine {
  # Minimum TLS 1.2, prefer 1.3
  protocol = "TLSv1.3"

  # Strong ciphers only
  enabled-algorithms = [
    "TLS_AES_256_GCM_SHA384",
    "TLS_AES_128_GCM_SHA256"
  ]

  # Require client auth (mutual TLS)
  require-mutual-authentication = on
}
```

#### Java HttpClient (to TfL API)

```java
// TfL API uses TLS 1.2/1.3 - Java 21 handles this automatically
// No configuration needed, but can enforce:
SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
HttpClient client = HttpClient.newBuilder()
    .sslContext(sslContext)
    .build();
```

### Ingress TLS (cert-manager)

```yaml
# Certificate
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: tfl-status-tls
  namespace: tfl-status
spec:
  secretName: tfl-status-tls
  issuerRef:
    name: letsencrypt-prod
    kind: ClusterIssuer
  dnsNames:
    - tfl-status.example.com

# Ingress with TLS
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: tfl-status
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
    # Force HTTPS redirect
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    # TLS version enforcement at ingress level
    nginx.ingress.kubernetes.io/ssl-protocols: "TLSv1.2 TLSv1.3"
spec:
  tls:
    - hosts:
        - tfl-status.example.com
      secretName: tfl-status-tls
  rules:
    - host: tfl-status.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: tfl-status
                port:
                  number: 8080
```

### Pekko Cluster TLS (Inter-node)

```hocon
# application.conf
pekko.remote.artery {
  transport = tls-tcp

  ssl.config-ssl-engine {
    key-store = "/certs/keystore.p12"
    key-store-password = ${KEYSTORE_PASSWORD}
    key-password = ${KEY_PASSWORD}

    trust-store = "/certs/truststore.p12"
    trust-store-password = ${TRUSTSTORE_PASSWORD}

    protocol = "TLSv1.3"
    enabled-algorithms = ["TLS_AES_256_GCM_SHA384"]
  }
}
```

### Certificate Generation (Cluster TLS)

```bash
# Generate CA
openssl genrsa -out ca.key 4096
openssl req -new -x509 -days 365 -key ca.key -out ca.crt -subj "/CN=tfl-cluster-ca"

# Generate node certificate
openssl genrsa -out node.key 4096
openssl req -new -key node.key -out node.csr -subj "/CN=tfl-status-node"
openssl x509 -req -days 365 -in node.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out node.crt

# Create PKCS12 keystore
openssl pkcs12 -export -out keystore.p12 -inkey node.key -in node.crt -certfile ca.crt

# Create truststore with CA
keytool -importcert -file ca.crt -alias ca -keystore truststore.p12 -storetype PKCS12
```

---

## 2. Network Policies

### Default Deny All

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
  namespace: tfl-status
spec:
  podSelector: {}
  policyTypes:
    - Ingress
    - Egress
```

### Allow Required Traffic

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: tfl-status-policy
  namespace: tfl-status
spec:
  podSelector:
    matchLabels:
      app: tfl-status
  policyTypes:
    - Ingress
    - Egress
  ingress:
    # From ingress controller
    - from:
        - namespaceSelector:
            matchLabels:
              name: ingress-nginx
      ports:
        - port: 8080
          protocol: TCP

    # From other tfl-status pods (cluster communication)
    - from:
        - podSelector:
            matchLabels:
              app: tfl-status
      ports:
        - port: 2551  # Pekko cluster
          protocol: TCP
        - port: 8558  # Pekko management
          protocol: TCP

    # From Prometheus (metrics scraping)
    - from:
        - namespaceSelector:
            matchLabels:
              name: monitoring
      ports:
        - port: 8080
          protocol: TCP

  egress:
    # To TfL API (HTTPS)
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0  # TfL API IPs not fixed
      ports:
        - port: 443
          protocol: TCP

    # To other tfl-status pods
    - to:
        - podSelector:
            matchLabels:
              app: tfl-status
      ports:
        - port: 2551
          protocol: TCP
        - port: 8558
          protocol: TCP

    # To DNS
    - to:
        - namespaceSelector: {}
          podSelector:
            matchLabels:
              k8s-app: kube-dns
      ports:
        - port: 53
          protocol: UDP
```

---

## 3. WAF Rules

### NGINX Ingress Rate Limiting

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: tfl-status
  annotations:
    # Rate limiting
    nginx.ingress.kubernetes.io/limit-rps: "10"
    nginx.ingress.kubernetes.io/limit-connections: "5"

    # Request size limits
    nginx.ingress.kubernetes.io/proxy-body-size: "1k"

    # Timeouts
    nginx.ingress.kubernetes.io/proxy-read-timeout: "30"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "30"

    # Security headers
    nginx.ingress.kubernetes.io/configuration-snippet: |
      add_header X-Frame-Options "DENY" always;
      add_header X-Content-Type-Options "nosniff" always;
      add_header X-XSS-Protection "1; mode=block" always;
      add_header Referrer-Policy "strict-origin-when-cross-origin" always;
```

### AWS WAF Rules (if using ALB)

```yaml
# WAF WebACL rules
Rules:
  # Rate limiting
  - Name: RateLimitRule
    Priority: 1
    Action:
      Block: {}
    Statement:
      RateBasedStatement:
        Limit: 1000
        AggregateKeyType: IP

  # SQL injection protection (mostly N/A for this service)
  - Name: SQLiRule
    Priority: 2
    OverrideAction:
      None: {}
    Statement:
      ManagedRuleGroupStatement:
        VendorName: AWS
        Name: AWSManagedRulesSQLiRuleSet

  # Known bad inputs
  - Name: CommonRuleSet
    Priority: 3
    OverrideAction:
      None: {}
    Statement:
      ManagedRuleGroupStatement:
        VendorName: AWS
        Name: AWSManagedRulesCommonRuleSet
```

### Application-Level Protection (Already Implemented)

| Protection | Implementation |
|------------|----------------|
| Rate limiting | RateLimiter.java (100 req/min per IP) |
| Input validation | Line ID checked against known values |
| Date validation | Date range validated in routes |
| Request timeout | Pekko HTTP request timeout |

---

## 4. Secrets Management

### Kubernetes Secrets (Basic)

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: tfl-status-secrets
  namespace: tfl-status
type: Opaque
data:
  # Base64 encoded
  keystore-password: <base64>
  truststore-password: <base64>
```

### Sealed Secrets (GitOps-friendly)

```bash
# Install sealed-secrets
kubectl apply -f https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.24.0/controller.yaml

# Seal a secret
kubeseal --format yaml < secret.yaml > sealed-secret.yaml

# sealed-secret.yaml can be committed to git
```

```yaml
apiVersion: bitnami.com/v1alpha1
kind: SealedSecret
metadata:
  name: tfl-status-secrets
  namespace: tfl-status
spec:
  encryptedData:
    keystore-password: AgBy8hCi...  # Encrypted, safe for git
```

### HashiCorp Vault (Enterprise)

```yaml
# Vault Agent injector annotations
apiVersion: apps/v1
kind: Deployment
metadata:
  name: tfl-status
spec:
  template:
    metadata:
      annotations:
        vault.hashicorp.com/agent-inject: "true"
        vault.hashicorp.com/role: "tfl-status"
        vault.hashicorp.com/agent-inject-secret-certs: "secret/data/tfl-status/certs"
        vault.hashicorp.com/agent-inject-template-certs: |
          {{- with secret "secret/data/tfl-status/certs" -}}
          {{ .Data.data.keystore | base64Decode }}
          {{- end }}
```

### External Secrets Operator vs CSI Driver

Two approaches for pulling secrets from external stores into Kubernetes:

| Aspect | External Secrets Operator | Secrets Store CSI Driver |
|--------|---------------------------|--------------------------|
| **How it works** | Controller syncs external secrets → K8s Secrets | Mounts secrets as files via CSI volume |
| **K8s Secret created?** | Yes (synced copy) | No (direct mount) |
| **Rotation** | Controller polls and updates | Rotation requires pod restart |
| **GitOps friendly** | Yes (ExternalSecret is declarative) | Yes (SecretProviderClass is declarative) |
| **Secret in etcd?** | Yes (as K8s Secret) | No (memory only) |
| **Supports** | Vault, AWS SM, GCP SM, Azure KV | Vault, AWS SM, GCP SM, Azure KV |

#### External Secrets Operator (Recommended for most cases)

```yaml
# ExternalSecret syncs from AWS Secrets Manager
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: tfl-status-certs
  namespace: tfl-status
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: aws-secrets-manager
    kind: ClusterSecretStore
  target:
    name: tfl-status-certs  # K8s Secret created
  data:
    - secretKey: keystore.p12
      remoteRef:
        key: tfl-status/certs
        property: keystore
```

#### Secrets Store CSI Driver (For high-security, no etcd storage)

```yaml
# SecretProviderClass for AWS Secrets Manager
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: tfl-status-certs
spec:
  provider: aws
  parameters:
    objects: |
      - objectName: "tfl-status/certs"
        objectType: "secretsmanager"

# Pod mounts secrets as volume (never in etcd)
spec:
  volumes:
    - name: secrets
      csi:
        driver: secrets-store.csi.k8s.io
        readOnly: true
        volumeAttributes:
          secretProviderClass: tfl-status-certs
  containers:
    - volumeMounts:
        - name: secrets
          mountPath: /mnt/secrets
          readOnly: true
```

#### Recommendation for This Service

**Use:** Sealed Secrets or External Secrets Operator

**Why:**
- We have minimal secrets (just optional TLS certs)
- CSI driver complexity not justified
- External Secrets Operator is simpler and GitOps-friendly
- If truly zero secrets needed, just use cert-manager for TLS (no manual secrets)

### Environment Variables (12-factor)

```yaml
env:
  - name: KEYSTORE_PASSWORD
    valueFrom:
      secretKeyRef:
        name: tfl-status-secrets
        key: keystore-password
```

---

## 5. RBAC

### Service Account

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: tfl-status
  namespace: tfl-status
```

### Role (Namespace-scoped)

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: tfl-status
  namespace: tfl-status
rules:
  # Pekko cluster needs to discover other pods
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list", "watch"]
```

### RoleBinding

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: tfl-status
  namespace: tfl-status
subjects:
  - kind: ServiceAccount
    name: tfl-status
roleRef:
  kind: Role
  name: tfl-status
  apiGroup: rbac.authorization.k8s.io
```

---

## 6. Security Checklist

### Pre-Production

- [ ] TLS on ingress (cert-manager configured)
- [ ] Network policies applied (default deny + allow list)
- [ ] Secrets not in git (sealed-secrets or external)
- [ ] RBAC configured (minimal permissions)
- [ ] Container runs as non-root
- [ ] Read-only root filesystem
- [ ] Resource limits set
- [ ] Security context configured

### Container Security Context

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 1000
  runAsGroup: 1000
  fsGroup: 1000
  readOnlyRootFilesystem: true
  allowPrivilegeEscalation: false
  capabilities:
    drop:
      - ALL
```

### Image Security

```dockerfile
# Use distroless or minimal base with pinned version
FROM eclipse-temurin:21.0.2_13-jre-alpine3.19

# Don't run as root
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy only what's needed
COPY --chown=appuser:appgroup build/libs/tfl-status.jar /app/app.jar

WORKDIR /app
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Vulnerability Scanning

**In practice, scan both Java packages and Docker containers:**

| Scan Target | Tool | When |
|-------------|------|------|
| Java dependencies | OWASP Dependency-Check, Snyk | CI build |
| Docker images | Trivy, Grype | CI build + registry |
| Base images | Trivy | Nightly scan |
| Container runtime | Falco | Production |

```yaml
# GitHub Actions - comprehensive scanning
- name: Scan Java dependencies
  uses: dependency-check/Dependency-Check_Action@main
  with:
    project: 'tfl-status'
    path: '.'
    format: 'HTML'
    args: '--failOnCVSS 7'

- name: Scan image for vulnerabilities
  uses: aquasecurity/trivy-action@master
  with:
    image-ref: ghcr.io/ig/tfl-status:${{ github.sha }}
    format: 'sarif'
    output: 'trivy-results.sarif'
    severity: 'CRITICAL,HIGH'

- name: Scan for secrets in image
  uses: aquasecurity/trivy-action@master
  with:
    image-ref: ghcr.io/ig/tfl-status:${{ github.sha }}
    scan-type: 'secret'
```

### Image Versioning (Reproducibility)

**Always use fixed, immutable image tags—never `latest`.**

| Image Reference | Status |
|-----------------|--------|
| `eclipse-temurin:21-jre-alpine` | **Bad** - alpine version floats |
| `eclipse-temurin:21.0.2_13-jre-alpine3.19` | **Good** - fully pinned |
| `cloudflare/cloudflared:latest` | **Bad** - unpredictable |
| `cloudflare/cloudflared:2024.1.2` | **Good** - immutable |
| `ghcr.io/ig/tfl-status:latest` | **Bad** - mutable in registry |
| `ghcr.io/ig/tfl-status:abc123` | **Good** - SHA-based |

**Why this matters:**
- Reproducible builds and deployments
- Known vulnerability surface (can scan specific version)
- Rollback to exact previous state
- Audit trail of what ran when

---

## 7. Pull-Based GitOps (Security Model)

### Push vs Pull Deployment

| Model | How It Works | Security Implications |
|-------|--------------|----------------------|
| **Push** | CI/CD pushes to cluster | Cluster credentials in CI, network path from CI to cluster |
| **Pull** | ArgoCD inside cluster pulls from Git | No inbound access needed, cluster pulls only |

### Pull-Based Benefits

```
┌─────────────────────────────────────────────────────────────────┐
│                        CLUSTER                                   │
│                   (Firewall: ingress HTTPS only)                │
│                                                                  │
│   ┌──────────────┐         ┌──────────────┐                     │
│   │   ArgoCD     │◄────────│  Git Repo    │ (outbound HTTPS)    │
│   │  (pulls)     │         │              │                     │
│   └──────┬───────┘         └──────────────┘                     │
│          │                                                       │
│          │ deploys                                               │
│          ▼                                                       │
│   ┌──────────────┐                                              │
│   │  tfl-status  │                                              │
│   │    pods      │                                              │
│   └──────────────┘                                              │
│                                                                  │
│   NO INBOUND PORTS for deployment                               │
│   CI never touches cluster directly                             │
└─────────────────────────────────────────────────────────────────┘
```

### ArgoCD Configuration (Pull-Based)

```yaml
# ArgoCD pulls manifests from Git - no push access needed
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: tfl-status
  namespace: argocd
spec:
  source:
    repoURL: https://github.com/ig/tfl-status  # ArgoCD pulls from here
    targetRevision: main
    path: helm/tfl-status
  destination:
    server: https://kubernetes.default.svc  # Local cluster
    namespace: tfl-status
  syncPolicy:
    automated:
      prune: true
      selfHeal: true  # Reverts manual changes
```

### CI Pipeline (No Cluster Access)

```yaml
# CI only pushes to Git, never to cluster
jobs:
  build:
    steps:
      - name: Build and push image
        run: docker push ghcr.io/ig/tfl-status:${{ github.sha }}

      - name: Update Git manifest
        run: |
          # CI updates Git, ArgoCD pulls the change
          git clone https://github.com/ig/gitops-repo
          yq -i '.image.tag = "${{ github.sha }}"' values.yaml
          git commit -am "Update image to ${{ github.sha }}"
          git push
          # CI NEVER runs kubectl, helm install, etc.
```

---

## 8. Network Hardening (Zero Trust)

### Cluster Firewall Rules

**Principle:** Cluster accepts nothing except HTTPS on ingress. All deployment is pull-based.

```
┌──────────────────────────────────────────────────────────────┐
│                     FIREWALL RULES                            │
├──────────────────────────────────────────────────────────────┤
│ INBOUND (Internet → Cluster)                                 │
│   ✓ TCP 443 (HTTPS) → Ingress Controller                    │
│   ✗ All other ports BLOCKED                                  │
│   ✗ TCP 22 (SSH) - use kubectl exec or bastion               │
│   ✗ TCP 6443 (K8s API) - internal only or VPN               │
├──────────────────────────────────────────────────────────────┤
│ OUTBOUND (Cluster → Internet)                                │
│   ✓ TCP 443 → api.tfl.gov.uk (TfL API)                      │
│   ✓ TCP 443 → ghcr.io (container registry)                  │
│   ✓ TCP 443 → github.com (ArgoCD pulls)                     │
│   ✓ TCP 443 → acme-v02.api.letsencrypt.org (cert-manager)   │
│   ✗ All other destinations BLOCKED                           │
└──────────────────────────────────────────────────────────────┘
```

### AWS Security Groups (Example)

```hcl
# Cluster nodes - no direct inbound
resource "aws_security_group" "cluster_nodes" {
  name = "tfl-status-nodes"

  # No inbound from internet
  # Only from load balancer
  ingress {
    from_port       = 0
    to_port         = 0
    protocol        = "-1"
    security_groups = [aws_security_group.alb.id]
  }

  # Outbound restricted
  egress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]  # Or specific IPs for TfL, registry
  }
}

# ALB - HTTPS only
resource "aws_security_group" "alb" {
  name = "tfl-status-alb"

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # No port 80 - redirect handled at ALB level
}
```

### Kubernetes Network Policies (Defense in Depth)

Even with cloud firewall, apply K8s network policies for namespace isolation:

```yaml
# Default deny all
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
  namespace: tfl-status
spec:
  podSelector: {}
  policyTypes:
    - Ingress
    - Egress

---
# Allow only what's needed
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: tfl-status-policy
  namespace: tfl-status
spec:
  podSelector:
    matchLabels:
      app: tfl-status
  policyTypes:
    - Ingress
    - Egress
  ingress:
    # Only from ingress controller
    - from:
        - namespaceSelector:
            matchLabels:
              name: ingress-nginx
      ports:
        - port: 8080
  egress:
    # To TfL API only (by DNS, or IP if known)
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0  # TfL IPs not static
      ports:
        - port: 443
    # DNS resolution
    - to:
        - namespaceSelector: {}
          podSelector:
            matchLabels:
              k8s-app: kube-dns
      ports:
        - port: 53
          protocol: UDP
```

### K8s API Access

```yaml
# K8s API should not be exposed to internet
# Access via:
# 1. VPN + kubectl
# 2. Bastion host
# 3. Cloud console (AWS/GCP)

# If must expose, use authorized networks:
# AWS EKS:
resource "aws_eks_cluster" "main" {
  vpc_config {
    endpoint_public_access  = true
    public_access_cidrs     = ["10.0.0.0/8"]  # Office IPs only
    endpoint_private_access = true
  }
}
```

---

## 9. Audit Logging

### Kubernetes Audit Policy

```yaml
apiVersion: audit.k8s.io/v1
kind: Policy
rules:
  # Log all requests to tfl-status namespace at Request level
  - level: Request
    namespaces: ["tfl-status"]
    verbs: ["create", "update", "patch", "delete"]
    resources:
      - group: ""
        resources: ["secrets", "configmaps"]
      - group: "apps"
        resources: ["deployments"]
```

### Application Audit Events

```java
// Log configuration changes
log.info("Config change: {} changed {} from {} to {}",
    user, setting, oldValue, newValue);

// Log authentication events (if added)
log.info("Auth: {} from {} - {}",
    action, clientIp, result);
```

---

## 10. Access Control (AuthN/AuthZ)

### Current State: No Auth

The service currently has **no authentication**. Anyone who can reach the endpoint can use it.

**Problem:** We don't want to be a public CDN for TfL data. Even though the data is public, we're paying for:
- Compute resources
- Network egress
- TfL API quota (if we ever need authenticated access)

### Options Comparison

| Approach | Complexity | User Experience | Suitable For |
|----------|------------|-----------------|--------------|
| **Internal network only** | Low | Seamless (if on VPN) | Traditional enterprise |
| **Cloudflare Access** | Low-Medium | Browser login, WARP client | Zero Trust, remote-first |
| **Tailscale** | Low | Install client, then seamless | Small teams, dev-friendly |
| **Google IAP** | Low-Medium | Google login | GCP environments |
| **OAuth2/OIDC in app** | High | Login flow | User-specific features |
| **mTLS (client certs)** | Medium | Cert management | Service-to-service |
| **API keys** | Medium | Key management | Programmatic access |

### Recommended: Zero Trust Proxy

For an internal service like this, **don't add auth to the application**. Put a Zero Trust proxy in front.

#### Option A: Cloudflare Access (Recommended)

```
User → Cloudflare Access → (authenticated) → Our Service
           │
           └─ Checks: corporate email, device posture, location
```

```yaml
# Cloudflare Access policy (conceptual)
application:
  name: tfl-status
  domain: tfl-status.ig.com

policies:
  - name: IG Employees Only
    decision: allow
    include:
      - email_domain: ig.com
    require:
      - device_posture: corporateDevice
```

**Benefits:**
- No code changes to service
- SSO with corporate IdP (Okta, Azure AD, Google)
- Device posture checks
- Audit logs
- Works for browser and API (via service tokens)

#### Option B: Tailscale

```
User (on Tailnet) → Tailscale → Our Service (private IP)
                         │
                         └─ Service not exposed to internet at all
```

```yaml
# Tailscale ACL
{
  "acls": [
    {
      "action": "accept",
      "src": ["group:traders"],
      "dst": ["tag:tfl-status:443"]
    }
  ],
  "groups": {
    "group:traders": ["user1@ig.com", "user2@ig.com"]
  }
}
```

**Benefits:**
- Service has no public IP at all
- Simple client (Tailscale app)
- No changes to service
- Built-in NAT traversal

#### Option C: VPN + Internal DNS (Traditional)

```
User → Corporate VPN → Internal Network → Service
                            │
                            └─ tfl-status.internal (not resolvable externally)
```

```yaml
# Ingress with internal-only class
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: tfl-status
  annotations:
    kubernetes.io/ingress.class: "internal-nginx"  # Internal LB only
spec:
  rules:
    - host: tfl-status.internal  # Not in public DNS
```

**Benefits:**
- No changes to service
- Uses existing VPN infrastructure
- Simple and proven

### Service-to-Service Auth (If Needed)

If other internal services call tfl-status (not just browsers):

#### mTLS (Mutual TLS)

```yaml
# Istio/Linkerd service mesh
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: tfl-status
  namespace: tfl-status
spec:
  mtls:
    mode: STRICT  # Require client cert
```

#### Service Tokens (Cloudflare Access)

```bash
# Service gets a token from Cloudflare
curl -H "CF-Access-Client-Id: $CLIENT_ID" \
     -H "CF-Access-Client-Secret: $CLIENT_SECRET" \
     https://tfl-status.ig.com/api/v1/tube/status
```

### What We're NOT Doing

| Approach | Why Not |
|----------|---------|
| OAuth2 in application | Over-engineering for internal service |
| API keys per user | Management overhead, we don't need user identity |
| IP allowlisting | Fragile, doesn't work for remote workers |
| Basic auth | Credentials in every request, no SSO |

### Recommendation for IG

Given IG is on-prem with some cloud migration:

1. **Short term:** Internal network only (VPN required)
   - Service on internal LB
   - No public DNS entry
   - Zero code changes

2. **Medium term:** Cloudflare Access or Tailscale
   - Enables secure remote access
   - Zero Trust model
   - Still zero code changes

3. **If user identity needed later:** Add OAuth2 with corporate IdP
   - But probably never needed for tube status

### Implementation: Internal-Only Ingress

```yaml
# AWS: Internal ALB
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: tfl-status
  annotations:
    alb.ingress.kubernetes.io/scheme: internal  # Not internet-facing
    alb.ingress.kubernetes.io/target-type: ip
spec:
  ingressClassName: alb
  rules:
    - host: tfl-status.internal.ig.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: tfl-status
                port:
                  number: 8080
```

```yaml
# On-prem: No external LoadBalancer, ClusterIP only
apiVersion: v1
kind: Service
metadata:
  name: tfl-status
spec:
  type: ClusterIP  # Only accessible within cluster or via VPN
  ports:
    - port: 8080
```

---

## 11. Compliance Notes

### Data Classification

| Data | Classification | Handling |
|------|----------------|----------|
| Tube status | Public | No special handling |
| Client IPs | PII (minimal) | Not logged/stored |
| Metrics | Internal | Standard retention |

### GDPR Considerations

- No user data collected
- Client IPs used only for rate limiting (not stored)
- Logs contain no PII
- TfL data is public

### SOC2 / ISO27001 Alignment

| Control | Status |
|---------|--------|
| Encryption in transit | ✓ TLS everywhere |
| Encryption at rest | N/A (no persistence) |
| Access control | ✓ RBAC + network policies |
| Audit logging | ✓ K8s audit + app logs |
| Vulnerability management | ✓ Image scanning |
