# Infrastructure

Terraform modules, cloud-agnostic design, and on-prem deployment options.

---

## 1. Design Principles

### Cloud-Agnostic Core

```
┌─────────────────────────────────────────────────────────────┐
│                    Application Layer                         │
│         (Same container, same Helm chart everywhere)         │
├─────────────────────────────────────────────────────────────┤
│                    Platform Layer                            │
│              (Kubernetes - any distribution)                 │
├─────────────────────────────────────────────────────────────┤
│                  Infrastructure Layer                        │
│   ┌─────────────┬─────────────┬─────────────┬─────────────┐ │
│   │    AWS      │    GCP      │    Azure    │   On-Prem   │ │
│   │    EKS      │    GKE      │    AKS      │  kubeadm/   │ │
│   │    ALB      │  Cloud LB   │  Azure LB   │  OpenShift  │ │
│   │    ECR      │    GCR      │    ACR      │  Harbor     │ │
│   └─────────────┴─────────────┴─────────────┴─────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### What Changes Per Environment

| Component | Cloud | On-Prem |
|-----------|-------|---------|
| Kubernetes | Managed (EKS/GKE/AKS) | Self-managed (kubeadm/OpenShift) |
| Load Balancer | Cloud LB | MetalLB / HAProxy / F5 |
| Container Registry | Cloud registry | Harbor / Nexus |
| DNS | Route53 / Cloud DNS | CoreDNS / External DNS |
| Certificates | cert-manager + Let's Encrypt | cert-manager + internal CA |
| Secrets | Cloud KMS | Vault / Sealed Secrets |

---

## 2. Terraform Module Structure

```
terraform/
├── modules/
│   ├── kubernetes-cluster/
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   ├── outputs.tf
│   │   └── providers/
│   │       ├── aws/
│   │       ├── gcp/
│   │       └── azure/
│   ├── networking/
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   └── outputs.tf
│   └── monitoring/
│       ├── main.tf
│       └── variables.tf
├── environments/
│   ├── staging/
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   └── terraform.tfvars
│   └── prod/
│       ├── main.tf
│       ├── variables.tf
│       └── terraform.tfvars
└── README.md
```

---

## 3. AWS EKS Deployment

### Terraform Configuration

```hcl
# environments/prod/main.tf

terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
  backend "s3" {
    bucket = "ig-terraform-state"
    key    = "tfl-status/prod/terraform.tfstate"
    region = "eu-west-1"
  }
}

provider "aws" {
  region = var.aws_region
}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "5.0.0"

  name = "tfl-status-${var.environment}"
  cidr = var.vpc_cidr

  azs             = var.availability_zones
  private_subnets = var.private_subnets
  public_subnets  = var.public_subnets

  enable_nat_gateway = true
  single_nat_gateway = var.environment != "prod"

  tags = var.tags
}

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "19.0.0"

  cluster_name    = "tfl-status-${var.environment}"
  cluster_version = "1.28"

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  eks_managed_node_groups = {
    main = {
      min_size     = var.node_min_size
      max_size     = var.node_max_size
      desired_size = var.node_desired_size

      instance_types = var.node_instance_types
      capacity_type  = "ON_DEMAND"
    }
  }

  tags = var.tags
}

# ALB Ingress Controller
module "alb_controller" {
  source = "../../modules/alb-controller"

  cluster_name = module.eks.cluster_name
  vpc_id       = module.vpc.vpc_id
}
```

### Variables

```hcl
# environments/prod/variables.tf

variable "aws_region" {
  default = "eu-west-1"
}

variable "environment" {
  default = "prod"
}

variable "vpc_cidr" {
  default = "10.0.0.0/16"
}

variable "availability_zones" {
  default = ["eu-west-1a", "eu-west-1b", "eu-west-1c"]
}

variable "private_subnets" {
  default = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
}

variable "public_subnets" {
  default = ["10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24"]
}

variable "node_instance_types" {
  default = ["t3.medium"]
}

variable "node_min_size" {
  default = 3
}

variable "node_max_size" {
  default = 10
}

variable "node_desired_size" {
  default = 3
}

variable "tags" {
  default = {
    Project     = "tfl-status"
    Environment = "prod"
    ManagedBy   = "terraform"
  }
}
```

---

## 4. GCP GKE Deployment

```hcl
# environments/prod-gcp/main.tf

terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
  }
  backend "gcs" {
    bucket = "ig-terraform-state"
    prefix = "tfl-status/prod"
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

module "gke" {
  source  = "terraform-google-modules/kubernetes-engine/google"
  version = "29.0.0"

  project_id        = var.project_id
  name              = "tfl-status-${var.environment}"
  region            = var.region
  network           = module.vpc.network_name
  subnetwork        = module.vpc.subnets_names[0]
  ip_range_pods     = "pods"
  ip_range_services = "services"

  node_pools = [
    {
      name               = "main-pool"
      machine_type       = "e2-medium"
      min_count          = 3
      max_count          = 10
      disk_size_gb       = 50
      disk_type          = "pd-standard"
      auto_repair        = true
      auto_upgrade       = true
    }
  ]
}
```

---

## 5. On-Premises Deployment

### Option A: kubeadm + MetalLB

```yaml
# MetalLB configuration
apiVersion: metallb.io/v1beta1
kind: IPAddressPool
metadata:
  name: tfl-status-pool
  namespace: metallb-system
spec:
  addresses:
    - 192.168.1.200-192.168.1.210

---
apiVersion: metallb.io/v1beta1
kind: L2Advertisement
metadata:
  name: tfl-status-l2
  namespace: metallb-system
spec:
  ipAddressPools:
    - tfl-status-pool
```

### Option B: HAProxy (Non-K8s)

```haproxy
# /etc/haproxy/haproxy.cfg

frontend tfl_status_front
    bind *:80
    bind *:443 ssl crt /etc/ssl/tfl-status.pem
    default_backend tfl_status_back

backend tfl_status_back
    balance roundrobin
    option httpchk GET /api/health/ready
    http-check expect status 200

    server node1 10.0.0.11:8080 check
    server node2 10.0.0.12:8080 check
    server node3 10.0.0.13:8080 check
```

### Option C: NGINX Load Balancer

```nginx
# /etc/nginx/conf.d/tfl-status.conf

upstream tfl_status {
    least_conn;
    server 10.0.0.11:8080 max_fails=3 fail_timeout=30s;
    server 10.0.0.12:8080 max_fails=3 fail_timeout=30s;
    server 10.0.0.13:8080 max_fails=3 fail_timeout=30s;
}

server {
    listen 80;
    listen 443 ssl;
    server_name tfl-status.internal;

    ssl_certificate /etc/ssl/tfl-status.crt;
    ssl_certificate_key /etc/ssl/tfl-status.key;

    location / {
        proxy_pass http://tfl_status;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_connect_timeout 5s;
        proxy_read_timeout 30s;
    }

    location /api/health {
        proxy_pass http://tfl_status;
        access_log off;
    }
}
```

### Bare Metal Setup (systemd)

```ini
# /etc/systemd/system/tfl-status.service

[Unit]
Description=TfL Tube Status Service
After=network.target

[Service]
Type=simple
User=tfl-status
Group=tfl-status
WorkingDirectory=/opt/tfl-status

Environment=TFL_NODE_ID=node-1
Environment=TFL_HTTP_PORT=8080
Environment=PEKKO_HOST=10.0.0.11
Environment=PEKKO_PORT=2551
Environment=PEKKO_SEED_NODES=10.0.0.11:2551,10.0.0.12:2551,10.0.0.13:2551

ExecStart=/usr/bin/java -jar /opt/tfl-status/tfl-status.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

---

## 6. Container Registry Options

### Cloud Registries

| Cloud | Registry | Auth |
|-------|----------|------|
| AWS | ECR | IAM role |
| GCP | GCR / Artifact Registry | Service account |
| Azure | ACR | Service principal |

### On-Prem Registries

```yaml
# Harbor installation (Helm)
helm repo add harbor https://helm.goharbor.io
helm install harbor harbor/harbor \
  --set expose.type=loadBalancer \
  --set externalURL=https://harbor.internal \
  --set persistence.enabled=true
```

---

## 7. DNS Configuration

### External DNS (Kubernetes)

```yaml
apiVersion: externaldns.k8s.io/v1alpha1
kind: DNSEndpoint
metadata:
  name: tfl-status
  namespace: tfl-status
spec:
  endpoints:
    - dnsName: tfl-status.example.com
      recordType: A
      targets:
        - <load-balancer-ip>
```

### On-Prem DNS (CoreDNS)

```
# Corefile addition
tfl-status.internal:53 {
    hosts {
        192.168.1.200 tfl-status.internal
        fallthrough
    }
}
```

---

## 8. Monitoring Infrastructure

### Prometheus Stack (Helm)

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm install prometheus prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --create-namespace \
  --set prometheus.prometheusSpec.retention=30d \
  --set prometheus.prometheusSpec.storageSpec.volumeClaimTemplate.spec.resources.requests.storage=50Gi
```

### ServiceMonitor for tfl-status

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: tfl-status
  namespace: monitoring
spec:
  selector:
    matchLabels:
      app: tfl-status
  namespaceSelector:
    matchNames:
      - tfl-status
  endpoints:
    - port: http
      path: /metrics
      interval: 30s
```

---

## 9. Disaster Recovery

### Backup Strategy

| Component | Backup Method | Frequency | Retention |
|-----------|--------------|-----------|-----------|
| K8s manifests | Git (GitOps) | Every change | Forever |
| Terraform state | S3/GCS versioning | Every apply | 90 days |
| Prometheus data | Thanos / remote write | Continuous | 30 days |
| Application data | N/A (stateless) | - | - |

### Multi-Region (if needed)

```
Primary: eu-west-1 (London)
    │
    ├─── Active traffic
    │
    └─── CRDT replication ───► Secondary: eu-west-2 (Ireland)
                                    │
                                    └─── Standby (or active-active)
```

**For this service:** Single region is sufficient. Tube data is only relevant to London.

---

## 10. Cost Estimates

### AWS (eu-west-1)

| Resource | Spec | Monthly Cost |
|----------|------|--------------|
| EKS cluster | 1 cluster | ~$73 |
| EC2 (3x t3.medium) | 3 nodes | ~$90 |
| ALB | 1 LB | ~$20 |
| NAT Gateway | 1 (staging: shared) | ~$32 |
| **Total** | | **~$215/month** |

### GCP (europe-west2)

| Resource | Spec | Monthly Cost |
|----------|------|--------------|
| GKE cluster | 1 cluster | ~$73 |
| Compute (3x e2-medium) | 3 nodes | ~$75 |
| Cloud LB | 1 LB | ~$18 |
| **Total** | | **~$166/month** |

### On-Prem

| Resource | Spec | Cost |
|----------|------|------|
| VMs | 3x 2vCPU/4GB | Existing infra |
| Load balancer | HAProxy on VM | Existing infra |
| **Total** | | **~$0 incremental** |

**Recommendation:** For IG's on-prem preference, use existing infrastructure. Cloud is available if needed for redundancy or burst capacity.

---

## 11. Migration Path

### From On-Prem to Cloud

```
Phase 1: Parallel deployment
    - Deploy to cloud (staging)
    - Validate with e2e tests

Phase 2: Traffic shift
    - Update DNS to point to cloud LB
    - Monitor for issues

Phase 3: Decommission
    - Remove on-prem instances
    - Update documentation
```

### From Cloud to On-Prem

Same process in reverse. The containerized, cloud-agnostic design makes this straightforward.
