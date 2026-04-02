# Duchess Cloud Infrastructure

AWS CDK infrastructure for the Duchess Construction Site Intelligence Platform.

## Architecture

```
SQS (escalation queue)
  └─→ Lambda (ppe-escalation)
        ├─→ Bedrock Claude 3.5 Sonnet (analysis)
        └─→ DynamoDB (alerts table)

S3 (video storage, KMS encrypted, 90-day lifecycle)
```

## Resources

| Resource | Purpose |
|----------|---------|
| S3 Bucket | Video segment storage (KMS encrypted, 90-day expiry) |
| DynamoDB Table | Safety alerts (id, timestamp, violationType, zoneId, severity, status) |
| SQS Queue | PPE escalation queue (5-min visibility, DLQ after 3 retries) |
| Lambda Function | Escalation handler — calls Bedrock, writes to DynamoDB |
| SageMaker Endpoint | PPE inference (placeholder — commented out for cost) |

## Prerequisites

- AWS CLI configured with appropriate credentials
- AWS CDK v2 installed: `npm install -g aws-cdk`
- Python 3.11+
- Poetry

## Setup

```bash
cd cloud/
poetry install

# Bootstrap CDK (first time only)
cdk bootstrap aws://ACCOUNT_ID/us-east-1

# Deploy
cdk deploy --context account=ACCOUNT_ID --context region=us-east-1
```

## Environment Variables (Lambda)

| Variable | Description |
|----------|-------------|
| `ALERTS_TABLE_NAME` | DynamoDB table name (set by CDK) |
| `VIDEO_BUCKET_NAME` | S3 bucket name (set by CDK) |
| `BEDROCK_MODEL_ID` | Bedrock model ID (default: Claude 3.5 Sonnet v2) |

## Privacy

- **No worker PII** is stored in DynamoDB — only anonymized IDs
- Video in S3 is KMS-encrypted and auto-expires after 90 days
- Bedrock analysis uses minimal context — no images sent to cloud
- All alert text is bilingual (EN + ES)

## Cost Optimization

- DynamoDB: PAY_PER_REQUEST billing (no idle cost)
- Lambda: 512MB, 60s timeout (right-sized for Bedrock calls)
- SageMaker endpoint: commented out — enable only when needed
- S3: 90-day lifecycle rule prevents storage accumulation
