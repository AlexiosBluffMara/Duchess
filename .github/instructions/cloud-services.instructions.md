---
description: "Use when working on AWS cloud services, nightly batch pipeline, immediate escalation API, or cloud cost management. Covers S3, Lambda, Bedrock, SageMaker, DynamoDB, SQS, SNS."
applyTo: ["cloud/**", "infra/**", "**/cdk/**"]
---

# Cloud Services Instructions

## Two Cloud Pipelines

### 1. Immediate Escalation (Real-Time Path)
Triggered when Gemma 3n confirms a PPE violation:
```
Phone → API Gateway (authenticated) → Lambda → Bedrock → 
Confidence > 0.85? → SQS Human Review Queue → Dashboard →
Human confirms → SNS → Push notification to mesh
```
- Latency budget: <30s total from phone to alert delivery
- Worker identifiers MUST be anonymized before Lambda processes them
- Dead-letter queue on SQS for failed messages
- API key authentication (not open endpoints)

### 2. Nightly Batch (Retrospective Analysis)
Runs at 2 AM local time on all video NOT already processed via escalation:
```
Phones upload to S3 (WiFi only, after shift) →
EventBridge trigger at 2 AM → Step Functions →
SageMaker Batch Transform (Qwen2.5-VL) → DynamoDB results →
Daily safety report → SNS to supervisors
```
- Upload only over WiFi (never cellular)
- S3 lifecycle: Standard → Glacier at 30 days → Delete at 90 days
- Batch results include: violation count, compliance rate, trends, near-misses

## Cloud Security Rules

- All S3 buckets: server-side encryption with KMS
- All Lambda: run in VPC, VPC endpoints for S3/DynamoDB
- No PII in CloudWatch logs (anonymize before logging)
- Billing alarms at 50%, 80%, 100% of monthly budget
- IAM roles: least-privilege, no `*` resource policies
- API Gateway: rate limiting, API key required, WAF enabled

## Cost Consciousness

- Use Bedrock on-demand pricing for escalation (pay per inference, no idle cost)
- Use SageMaker batch transform for nightly (cheaper than real-time endpoints)
- S3 Intelligent-Tiering for video storage
- Lambda: set memory to minimum needed (measure, don't guess)
- Review monthly cost reports and optimize
