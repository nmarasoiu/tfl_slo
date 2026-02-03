# Deployment

Helm charts, GitOps workflow, and deployment strategies.

---

## 1. Helm Chart Structure

```
helm/tfl-status/
├── Chart.yaml
├── values.yaml
├── values-staging.yaml
├── values-prod.yaml
├── templates/
│   ├── _helpers.tpl
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── ingress.yaml
│   ├── configmap.yaml
│   ├── serviceaccount.yaml
│   ├── hpa.yaml
│   ├── pdb.yaml
│   └── servicemonitor.yaml
└── tests/
    └── test-connection.yaml
```

### Chart.yaml

```yaml
apiVersion: v2
name: tfl-status
description: TfL Tube Status Service - Resilient cache for London Underground status
type: application
version: 1.0.0
appVersion: "1.0.0"
keywords:
  - tfl
  - tube
  - cache
  - pekko
maintainers:
  - name: SRE Team
    email: sre@ig.com
```

### values.yaml (defaults)

```yaml
# Image
image:
  repository: ghcr.io/ig/tfl-status
  tag: latest
  pullPolicy: IfNotPresent

# Replicas
replicaCount: 3

# Node identity (templated per pod)
nodeId: "{{ .Release.Name }}-{{ .pod.name }}"

# Ports
service:
  type: ClusterIP
  httpPort: 8080
  clusterPort: 2551

# Resources
resources:
  requests:
    cpu: 100m
    memory: 256Mi
  limits:
    cpu: 500m
    memory: 512Mi

# Pekko cluster
pekko:
  cluster:
    name: tfl-cluster
    seedNodes: []  # Auto-discovered via K8s API
  management:
    httpPort: 8558

# Health checks
probes:
  liveness:
    path: /api/health/live
    initialDelaySeconds: 10
    periodSeconds: 10
  readiness:
    path: /api/health/ready
    initialDelaySeconds: 5
    periodSeconds: 5

# Ingress
ingress:
  enabled: true
  className: nginx
  annotations:
    nginx.ingress.kubernetes.io/rate-limit: "100"
    nginx.ingress.kubernetes.io/rate-limit-window: "1m"
  hosts:
    - host: tfl-status.internal
      paths:
        - path: /
          pathType: Prefix

# Autoscaling
autoscaling:
  enabled: false
  minReplicas: 3
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70

# Pod Disruption Budget
pdb:
  enabled: true
  minAvailable: 2

# Service Account
serviceAccount:
  create: true
  annotations: {}

# Monitoring
monitoring:
  enabled: true
  serviceMonitor:
    interval: 30s
    path: /metrics
```

### values-staging.yaml

```yaml
replicaCount: 2

resources:
  requests:
    cpu: 50m
    memory: 128Mi
  limits:
    cpu: 200m
    memory: 256Mi

ingress:
  hosts:
    - host: tfl-status.staging.internal

pdb:
  minAvailable: 1
```

### values-prod.yaml

```yaml
replicaCount: 3

resources:
  requests:
    cpu: 100m
    memory: 256Mi
  limits:
    cpu: 500m
    memory: 512Mi

ingress:
  annotations:
    nginx.ingress.kubernetes.io/rate-limit: "100"
    nginx.ingress.kubernetes.io/rate-limit-window: "1m"
    nginx.ingress.kubernetes.io/proxy-read-timeout: "30"
  hosts:
    - host: tfl-status.prod.internal

autoscaling:
  enabled: true
  minReplicas: 3
  maxReplicas: 10

pdb:
  minAvailable: 2
```

### templates/deployment.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "tfl-status.fullname" . }}
  labels:
    {{- include "tfl-status.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      {{- include "tfl-status.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "tfl-status.selectorLabels" . | nindent 8 }}
        pekko-cluster: {{ .Release.Name }}
    spec:
      serviceAccountName: {{ include "tfl-status.serviceAccountName" . }}
      containers:
        - name: tfl-status
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ .Values.service.httpPort }}
            - name: cluster
              containerPort: {{ .Values.service.clusterPort }}
            - name: management
              containerPort: {{ .Values.pekko.management.httpPort }}
          env:
            - name: TFL_NODE_ID
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: TFL_HTTP_PORT
              value: "{{ .Values.service.httpPort }}"
            - name: PEKKO_HOST
              valueFrom:
                fieldRef:
                  fieldPath: status.podIP
            - name: PEKKO_PORT
              value: "{{ .Values.service.clusterPort }}"
            - name: PEKKO_CLUSTER_NAME
              value: {{ .Values.pekko.cluster.name }}
          livenessProbe:
            httpGet:
              path: {{ .Values.probes.liveness.path }}
              port: http
            initialDelaySeconds: {{ .Values.probes.liveness.initialDelaySeconds }}
            periodSeconds: {{ .Values.probes.liveness.periodSeconds }}
          readinessProbe:
            httpGet:
              path: {{ .Values.probes.readiness.path }}
              port: http
            initialDelaySeconds: {{ .Values.probes.readiness.initialDelaySeconds }}
            periodSeconds: {{ .Values.probes.readiness.periodSeconds }}
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
      maxSurge: 1
```

### templates/pdb.yaml

```yaml
{{- if .Values.pdb.enabled }}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "tfl-status.fullname" . }}
spec:
  minAvailable: {{ .Values.pdb.minAvailable }}
  selector:
    matchLabels:
      {{- include "tfl-status.selectorLabels" . | nindent 6 }}
{{- end }}
```

---

## 2. GitOps Workflow

### Repository Structure

```
gitops-repo/
├── apps/
│   └── tfl-status/
│       ├── base/
│       │   ├── kustomization.yaml
│       │   └── namespace.yaml
│       └── overlays/
│           ├── staging/
│           │   ├── kustomization.yaml
│           │   └── values.yaml
│           └── prod/
│               ├── kustomization.yaml
│               └── values.yaml
└── clusters/
    ├── staging/
    │   └── tfl-status.yaml  # ArgoCD Application
    └── prod/
        └── tfl-status.yaml
```

### ArgoCD Application

```yaml
# clusters/prod/tfl-status.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: tfl-status
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/ig/tfl-status
    targetRevision: main
    path: helm/tfl-status
    helm:
      valueFiles:
        - values.yaml
        - values-prod.yaml
  destination:
    server: https://kubernetes.default.svc
    namespace: tfl-status
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

### Deployment Flow

```
Developer pushes code
        │
        ▼
   CI Pipeline (GitHub Actions)
        │
        ├─► Build & test
        ├─► Build container image
        ├─► Push to registry (ghcr.io)
        └─► Update image tag in gitops-repo
                │
                ▼
        ArgoCD detects change
                │
                ├─► Staging: Auto-sync
                │       │
                │       ▼
                │   Staging tests pass?
                │       │
                │       ▼
                └─► Prod: Manual approval → Sync
```

### GitHub Actions CI

```yaml
# .github/workflows/ci.yaml
name: CI/CD

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Run tests
        run: ./gradlew test
      - name: Run e2e tests
        run: ./gradlew e2eTest

  build:
    needs: test
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Build image
        run: docker build -t ghcr.io/ig/tfl-status:${{ github.sha }} .
      - name: Push image
        run: |
          echo ${{ secrets.GITHUB_TOKEN }} | docker login ghcr.io -u ${{ github.actor }} --password-stdin
          docker push ghcr.io/ig/tfl-status:${{ github.sha }}
          docker tag ghcr.io/ig/tfl-status:${{ github.sha }} ghcr.io/ig/tfl-status:latest
          docker push ghcr.io/ig/tfl-status:latest

  update-gitops:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - name: Update image tag
        run: |
          # Clone gitops repo, update tag, push
          git clone https://github.com/ig/gitops-repo
          cd gitops-repo
          yq -i '.spec.source.helm.parameters[0].value = "${{ github.sha }}"' clusters/staging/tfl-status.yaml
          git commit -am "Update tfl-status to ${{ github.sha }}"
          git push
```

---

## 3. Rolling Update Strategy

### Default Strategy

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 1   # At most 1 pod down at a time
    maxSurge: 1         # At most 1 extra pod during update
```

### Update Sequence (3 replicas)

```
Initial state: [Pod1-v1, Pod2-v1, Pod3-v1]
                   │
Step 1: Create Pod4-v2 (maxSurge=1)
        [Pod1-v1, Pod2-v1, Pod3-v1, Pod4-v2(starting)]
                   │
Step 2: Pod4-v2 ready, terminate Pod1-v1 (maxUnavailable=1)
        [Pod2-v1, Pod3-v1, Pod4-v2, Pod1-v1(terminating)]
                   │
Step 3: Create Pod5-v2
        [Pod2-v1, Pod3-v1, Pod4-v2, Pod5-v2(starting)]
                   │
Step 4: Pod5-v2 ready, terminate Pod2-v1
        [Pod3-v1, Pod4-v2, Pod5-v2, Pod2-v1(terminating)]
                   │
Step 5: Create Pod6-v2
        [Pod3-v1, Pod4-v2, Pod5-v2, Pod6-v2(starting)]
                   │
Step 6: Pod6-v2 ready, terminate Pod3-v1
        [Pod4-v2, Pod5-v2, Pod6-v2]
                   │
Final: [Pod4-v2, Pod5-v2, Pod6-v2]
```

### Graceful Shutdown

```java
// TflApplication.java - already implemented
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    log.info("Shutting down...");
    serverBinding.unbind();  // Stop accepting new requests
    // Pekko coordinated shutdown handles the rest
    system.terminate();
}));
```

### Pod Lifecycle

```yaml
spec:
  terminationGracePeriodSeconds: 30  # Time to finish in-flight requests
  containers:
    - lifecycle:
        preStop:
          exec:
            command: ["sh", "-c", "sleep 5"]  # Allow LB to drain
```

---

## 4. Canary Deployments (Optional)

Using Argo Rollouts:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: tfl-status
spec:
  replicas: 3
  strategy:
    canary:
      steps:
        - setWeight: 10
        - pause: {duration: 5m}
        - setWeight: 30
        - pause: {duration: 5m}
        - setWeight: 50
        - pause: {duration: 5m}
        - setWeight: 100
      canaryMetadata:
        labels:
          role: canary
      stableMetadata:
        labels:
          role: stable
```

**For this service:** Canary is overkill. Rolling updates with PDB are sufficient.

---

## 5. Rollback

### Automatic (ArgoCD)

```yaml
syncPolicy:
  automated:
    selfHeal: true  # Auto-rollback if manual changes detected
```

### Manual Rollback

```bash
# ArgoCD CLI
argocd app rollback tfl-status

# Or kubectl
kubectl rollout undo deployment/tfl-status

# Or Helm
helm rollback tfl-status 1
```

### Rollback Triggers

| Trigger | Action |
|---------|--------|
| Readiness probe fails for 3+ pods | K8s halts rollout |
| Error rate spikes | Manual rollback (or automated with Argo Rollouts) |
| Latency SLO breach | Manual rollback |

---

## 6. Environment Promotion

```
main branch
    │
    ▼
  Build → Image: v1.2.3
    │
    ├───────────────────────────────────────┐
    ▼                                       │
Staging (auto-deploy)                       │
    │                                       │
    ├─ E2E tests pass?                      │
    │      │                                │
    │      ▼ Yes                            │
    │  Create PR to promote                 │
    │      │                                │
    ▼      ▼                                │
Prod (manual approval)◄─────────────────────┘
```

### Promotion Script

```bash
#!/bin/bash
# promote.sh <version> <from-env> <to-env>

VERSION=$1
FROM=$2
TO=$3

# Verify version exists in FROM
kubectl --context=$FROM get deployment tfl-status -o jsonpath='{.spec.template.spec.containers[0].image}' | grep $VERSION

# Update TO environment
cd gitops-repo
yq -i ".spec.source.helm.parameters[0].value = \"$VERSION\"" clusters/$TO/tfl-status.yaml
git commit -am "Promote tfl-status $VERSION from $FROM to $TO"
git push

echo "Promotion initiated. Awaiting ArgoCD sync..."
```
