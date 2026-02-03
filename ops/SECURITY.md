# Security

TLS, network policies, WAF, and secrets management.

---

## 1. TLS Configuration

### Traffic Encryption Matrix

| Connection | Current | Production | Implementation |
|------------|---------|------------|----------------|
| Client → Ingress | HTTP | HTTPS | cert-manager + Let's Encrypt |
| Ingress → Service | HTTP | HTTP (internal) | K8s network policy |
| Service → TfL API | HTTPS | HTTPS | Already implemented |
| Node ↔ Node (Pekko) | Unencrypted | TLS | Pekko Artery SSL |

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
# Use distroless or minimal base
FROM eclipse-temurin:21-jre-alpine

# Don't run as root
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy only what's needed
COPY --chown=appuser:appgroup build/libs/tfl-status.jar /app/app.jar

WORKDIR /app
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Vulnerability Scanning

```yaml
# GitHub Actions step
- name: Scan image for vulnerabilities
  uses: aquasecurity/trivy-action@master
  with:
    image-ref: ghcr.io/ig/tfl-status:${{ github.sha }}
    format: 'sarif'
    output: 'trivy-results.sarif'
    severity: 'CRITICAL,HIGH'
```

---

## 7. Audit Logging

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

## 8. Compliance Notes

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
