"""
Pytest fixtures for Duchess cloud tests.

Jordan: These fixtures set up mock AWS services via moto so we never touch
real AWS during tests. Every test runs against local mocks — zero cost,
zero blast radius, zero chance of accidentally deleting prod data.

Fixture hierarchy:
  aws_credentials → mock_dynamodb / mock_sqs / mock_sns / mock_bedrock
  sample_escalation_event → used by handler tests
  dynamodb_table → pre-provisioned table for handler tests
"""

from __future__ import annotations

import json
import os
from unittest.mock import MagicMock, patch

import boto3
import pytest
from moto import mock_aws


# ── Environment Setup ───────────────────────────────────────────────────────
# Jordan: Set environment variables BEFORE importing the handler module.
# Lambda reads env vars at module load time, so we need these set first.
# These are all fake values — moto doesn't care about real ARNs.

@pytest.fixture(autouse=True)
def _set_env_vars(monkeypatch):
    """
    Jordan: Set all Lambda environment variables for every test.
    autouse=True means this runs before every test function automatically.
    No test should depend on real env vars or fail because of missing config.
    """
    monkeypatch.setenv("ALERTS_TABLE_NAME", "duchess-alerts-test")
    monkeypatch.setenv("VIDEO_BUCKET_NAME", "duchess-video-test")
    monkeypatch.setenv("BEDROCK_MODEL_ID", "anthropic.claude-3-5-sonnet-20241022-v2:0")
    monkeypatch.setenv("ALERT_TOPIC_ARN", "arn:aws:sns:us-east-1:123456789012:duchess-alerts-test")
    monkeypatch.setenv("STAGE", "test")
    monkeypatch.setenv("LOG_LEVEL", "DEBUG")
    monkeypatch.setenv("AWS_DEFAULT_REGION", "us-east-1")
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "testing")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "testing")
    monkeypatch.setenv("AWS_SECURITY_TOKEN", "testing")
    monkeypatch.setenv("AWS_SESSION_TOKEN", "testing")


# ── Mock AWS Services ───────────────────────────────────────────────────────

@pytest.fixture
def mock_aws_services():
    """
    Jordan: Start ALL mock AWS services at once. moto's mock_aws() patches
    boto3 globally — any boto3 client created during the test talks to the
    local mock, not real AWS. This is the foundation for all our tests.

    Cost of running this: $0.00
    Cost of accidentally hitting real AWS in tests: $career
    """
    with mock_aws():
        yield


@pytest.fixture
def dynamodb_table(mock_aws_services):
    """
    Jordan: Create a DynamoDB table matching our CDK stack's schema.
    This is the alerts table that the Lambda handler writes to.
    Schema: id (HASH) + timestamp (RANGE), with zone-severity GSI.
    """
    client = boto3.resource("dynamodb", region_name="us-east-1")
    table = client.create_table(
        TableName="duchess-alerts-test",
        KeySchema=[
            {"AttributeName": "id", "KeyType": "HASH"},
            {"AttributeName": "timestamp", "KeyType": "RANGE"},
        ],
        AttributeDefinitions=[
            {"AttributeName": "id", "AttributeType": "S"},
            {"AttributeName": "timestamp", "AttributeType": "N"},
            {"AttributeName": "zoneId", "AttributeType": "S"},
            {"AttributeName": "severity", "AttributeType": "N"},
        ],
        GlobalSecondaryIndexes=[
            {
                "IndexName": "zone-severity-index",
                "KeySchema": [
                    {"AttributeName": "zoneId", "KeyType": "HASH"},
                    {"AttributeName": "severity", "KeyType": "RANGE"},
                ],
                "Projection": {"ProjectionType": "ALL"},
            },
        ],
        BillingMode="PAY_PER_REQUEST",
    )
    table.meta.client.get_waiter("table_exists").wait(TableName="duchess-alerts-test")
    return table


@pytest.fixture
def sqs_queue(mock_aws_services):
    """
    Jordan: Create a mock SQS queue for escalation messages.
    Matches the CDK stack's queue configuration.
    """
    client = boto3.client("sqs", region_name="us-east-1")
    response = client.create_queue(
        QueueName="duchess-escalation-test",
        Attributes={
            "VisibilityTimeout": "300",
            "MessageRetentionPeriod": "345600",
        },
    )
    return response["QueueUrl"]


@pytest.fixture
def sns_topic(mock_aws_services):
    """
    Jordan: Create a mock SNS topic for alert notifications.
    """
    client = boto3.client("sns", region_name="us-east-1")
    response = client.create_topic(Name="duchess-alerts-test")
    return response["TopicArn"]


# ── Sample Events ───────────────────────────────────────────────────────────

@pytest.fixture
def sample_escalation():
    """
    Jordan: A minimal valid escalation payload as it arrives from the phone app.
    This is what the SQS message body looks like after JSON parsing.
    Note: anonymized_worker_id is a hash, NOT a name or badge number.
    """
    return {
        "source_event_id": "evt-test-001",
        "violation_type": "missing_hardhat",
        "zone_id": "zone-A-framing",
        "severity": 3,
        "description": "Worker on scaffolding without hard hat, second floor framing area",
        "anonymized_worker_id": "anon-abc123",
    }


@pytest.fixture
def sample_sqs_event(sample_escalation):
    """
    Jordan: A complete SQS event as Lambda receives it.
    The escalation payload is JSON-encoded in the 'body' field.
    """
    return {
        "Records": [
            {
                "messageId": "msg-test-001",
                "receiptHandle": "handle-test-001",
                "body": json.dumps(sample_escalation),
                "attributes": {},
                "messageAttributes": {},
                "md5OfBody": "",
                "eventSource": "aws:sqs",
                "eventSourceARN": "arn:aws:sqs:us-east-1:123456789012:duchess-escalation-test",
                "awsRegion": "us-east-1",
            }
        ]
    }


@pytest.fixture
def sample_multi_record_event():
    """
    Jordan: SQS event with multiple records for batch processing tests.
    Each record is a separate PPE violation.
    """
    records = []
    for i in range(3):
        escalation = {
            "source_event_id": f"evt-multi-{i:03d}",
            "violation_type": ["missing_hardhat", "missing_vest", "missing_gloves"][i],
            "zone_id": f"zone-{chr(65 + i)}",
            "severity": i + 1,
            "description": f"Test violation {i}",
            "anonymized_worker_id": f"anon-worker-{i:03d}",
        }
        records.append({
            "messageId": f"msg-multi-{i:03d}",
            "receiptHandle": f"handle-multi-{i:03d}",
            "body": json.dumps(escalation),
            "attributes": {},
            "messageAttributes": {},
            "md5OfBody": "",
            "eventSource": "aws:sqs",
            "eventSourceARN": "arn:aws:sqs:us-east-1:123456789012:duchess-escalation-test",
            "awsRegion": "us-east-1",
        })
    return {"Records": records}


@pytest.fixture
def sample_escalation_with_pii():
    """
    Jordan: An escalation payload that contains PII fields that MUST be stripped.
    This simulates a buggy phone app sending data it shouldn't.
    The handler MUST strip these before writing to DynamoDB.
    """
    return {
        "source_event_id": "evt-pii-001",
        "violation_type": "missing_hardhat",
        "zone_id": "zone-A",
        "severity": 2,
        "description": "Worker without hard hat",
        "anonymized_worker_id": "anon-xyz789",
        # Jordan: These fields should NEVER make it to DynamoDB
        "worker_name": "John Doe",
        "email": "jdoe@example.com",
        "phone_number": "555-0123",
        "badge_number": "B-9876",
        "exact_gps": "40.7128,-74.0060",
    }


@pytest.fixture
def mock_bedrock_response():
    """
    Jordan: A mock Bedrock Claude response for tests that need to simulate
    a successful Bedrock analysis call.
    """
    return {
        "description_en": "Worker detected without required hard hat in active framing zone.",
        "description_es": "Trabajador detectado sin casco requerido en zona de armado activa.",
        "recommended_action": "Provide hard hat immediately and conduct toolbox talk on head protection.",
        "osha_reference": "OSHA 1926.100 - Head Protection",
    }
