# Infrastructure

Cloud-agnostic design and platform options.

---

## Summary

This service is **stateless** and **cloud-agnostic**. The same container runs anywhere.

| Platform | Load Balancer | Notes |
|----------|---------------|-------|
| AWS EKS | ALB/NLB | Most common cloud |
| GCP GKE | Cloud LB | Alternative cloud |
| Azure AKS | Azure LB | Alternative cloud |
| On-prem K8s | MetalLB / HAProxy | Current IG setup |
| Bare metal | HAProxy / Nginx | Non-K8s option |

---

## Architecture Layers

```
┌─────────────────────────────────────────────────────────┐
│                  Application Layer                       │
│         (Same container, same Helm chart everywhere)     │
├─────────────────────────────────────────────────────────┤
│                   Platform Layer                         │
│              (Kubernetes - any distribution)             │
├─────────────────────────────────────────────────────────┤
│                 Infrastructure Layer                     │
│   AWS EKS  │  GCP GKE  │  Azure AKS  │  On-Prem K8s    │
└─────────────────────────────────────────────────────────┘
```

---

## What Changes Per Environment

| Component | Cloud | On-Prem |
|-----------|-------|---------|
| Kubernetes | Managed (EKS/GKE/AKS) | kubeadm / OpenShift |
| Load Balancer | Cloud LB | MetalLB / HAProxy |
| Container Registry | Cloud registry | Harbor / Nexus |
| DNS | Route53 / Cloud DNS | CoreDNS |
| Certificates | cert-manager + Let's Encrypt | cert-manager + internal CA |
| Secrets | Cloud KMS | Vault / Sealed Secrets |

---

## Terraform Structure (If Needed)

```
terraform/
├── modules/
│   ├── kubernetes-cluster/   # EKS/GKE/AKS-specific
│   └── networking/           # VPC, subnets, security groups
└── environments/
    ├── staging/
    │   └── terraform.tfvars
    └── prod/
        └── terraform.tfvars
```

---

## Resource Requirements

| Environment | Nodes | Pod Resources |
|-------------|-------|---------------|
| Dev | 1 node | 100m CPU, 128Mi |
| Staging | 2 nodes | 100m CPU, 256Mi |
| Prod | 3+ nodes | 200m CPU, 512Mi |

**Note:** A single node can handle actual IG load (~500 req/min peak). We run 3+ nodes for:
1. High availability (survive node failures)
2. Zero-downtime deployments
3. Demonstrating distributed patterns

---

*Full Terraform modules for AWS/GCP/Azure available on request. Omitted here as infrastructure is typically managed by platform teams.*
