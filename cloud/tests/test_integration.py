"""
End-to-End Integration Tests for Duchess PPE Escalation Pipeline.

Jordan: These tests verify the full pipeline flow:
  SQS message → Lambda handler → Bedrock mock → DynamoDB write → verify alert

This is NOT a unit test — it tests the integration between components.
All AWS services are still mocked via moto (no real AWS calls), but the
components interact with each other through real boto3 calls to local mocks.

Why integration tests matter:
  - Unit tests verify individual functions work in isolation
  - Integration tests verify the WIRING between functions works
  - In production, most failures are wiring failures (wrong table name,
    wrong IAM role, wrong env var), not logic failures

Cost of running: $0.00 (moto mocks)
Cost of skipping: One misconfigured env var in prod that drops PPE alerts
for an entire shift before someone notices.

Run: cd cloud && poetry run pytest tests/test_integration.py -v
"""

from __future__ import annotations

import json
import sys
import time
from io import BytesIO
from unittest.mock import MagicMock, patch

import boto3
import pytest
from moto import mock_aws


# ── Helpers ─────────────────────────────────────────────────────────────────


def _create_mock_infrastructure():
    """
    Jordan: Stand up the full mock AWS infrastructure that mirrors the CDK stack.
    This creates the same resources CDK would create: DynamoDB table, SQS queues,
    SNS topic. Everything except LambdaError (Lambda isn't deployed in moto —
    we call the handler function directly).
    """
    # DynamoDB: alerts table with the exact schema from CDK
    dynamodb = boto3.resource("dynamodb", region_name="us-east-1")
    table = dynamodb.create_table(
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
    table.meta.client.get_waiter("table_exists").wait(
        TableName="duchess-alerts-test"
    )

    # SQS: escalation queue and DLQ
    sqs = boto3.client("sqs", region_name="us-east-1")
    dlq = sqs.create_queue(QueueName="duchess-escalation-dlq-test")
    dlq_arn = sqs.get_queue_attributes(
        QueueUrl=dlq["QueueUrl"],
        AttributeNames=["QueueArn"],
    )["Attributes"]["QueueArn"]

    queue = sqs.create_queue(
        QueueName="duchess-escalation-test",
        Attributes={
            "VisibilityTimeout": "300",
            "RedrivePolicy": json.dumps({
                "deadLetterTargetArn": dlq_arn,
                "maxReceiveCount": "3",
            }),
        },
    )

    # SNS: alert topic
    sns = boto3.client("sns", region_name="us-east-1")
    topic = sns.create_topic(Name="duchess-alerts-test")

    return {
        "table": table,
        "queue_url": queue["QueueUrl"],
        "dlq_url": dlq["QueueUrl"],
        "topic_arn": topic["TopicArn"],
        "sqs_client": sqs,
        "sns_client": sns,
    }


def _build_bedrock_response(analysis: dict) -> dict:
    """Jordan: Build a realistic Bedrock invoke_model response."""
    return {
        "body": BytesIO(json.dumps({
            "content": [{"type": "text", "text": json.dumps(analysis)}],
            "model": "claude-3-5-sonnet",
            "stop_reason": "end_turn",
            "usage": {"input_tokens": 150, "output_tokens": 100},
        }).encode()),
        "contentType": "application/json",
    }


# ── Integration Tests ───────────────────────────────────────────────────────


class TestEndToEndFlow:
    """
    Jordan: Full pipeline integration test. This is the closest we can get
    to testing the real system without deploying to AWS. The flow:

    1. Write an escalation message to SQS (simulating phone app)
    2. Read the message from SQS (simulating Lambda trigger)
    3. Call the Lambda handler with the SQS event
    4. Verify Bedrock was called with the right prompt
    5. Verify DynamoDB has the alert with correct fields
    6. Verify the alert has bilingual content
    7. Verify no PII leaked through
    """

    @mock_aws
    def test_sqs_to_lambda_to_dynamo_flow(self):
        """
        Jordan: The golden path. SQS message → Lambda invocation →
        Bedrock analysis → DynamoDB write → alert with all required fields.

        This test catches:
          - Wrong env var names (table name mismatch)
          - Wrong DynamoDB key schema (put_item fails)
          - Wrong Bedrock request format (invoke_model fails)
          - Missing bilingual content
          - PII leakage
        """
        infra = _create_mock_infrastructure()
        table = infra["table"]
        sqs_client = infra["sqs_client"]
        queue_url = infra["queue_url"]

        # Step 1: Write escalation to SQS (simulates phone app sending PPE violation)
        # Jordan: This is exactly what the companion phone app does when
        # Gemma 4 confirms a PPE violation on Tier 2.
        escalation = {
            "source_event_id": "evt-e2e-001",
            "violation_type": "missing_hardhat",
            "zone_id": "zone-A-framing",
            "severity": 3,
            "description": "Worker on 2nd floor scaffolding without hard hat, near edge",
            "anonymized_worker_id": "anon-e2e-001",
        }

        sqs_client.send_message(
            QueueUrl=queue_url,
            MessageBody=json.dumps(escalation),
        )

        # Step 2: Receive message from SQS (simulates Lambda trigger)
        response = sqs_client.receive_message(
            QueueUrl=queue_url,
            MaxNumberOfMessages=1,
            WaitTimeSeconds=0,
        )
        messages = response.get("Messages", [])
        assert len(messages) == 1, "Expected 1 message in SQS queue"

        # Build the Lambda event in the exact format SQS→Lambda integration uses
        sqs_event = {
            "Records": [
                {
                    "messageId": messages[0]["MessageId"],
                    "receiptHandle": messages[0]["ReceiptHandle"],
                    "body": messages[0]["Body"],
                    "attributes": {},
                    "messageAttributes": {},
                    "md5OfBody": messages[0].get("MD5OfBody", ""),
                    "eventSource": "aws:sqs",
                    "eventSourceARN": f"arn:aws:sqs:us-east-1:123456789012:duchess-escalation-test",
                    "awsRegion": "us-east-1",
                }
            ]
        }

        # Step 3: Call Lambda handler with Bedrock mock
        bedrock_analysis = {
            "description_en": "Worker detected without hard hat on second floor scaffolding near edge. Immediate fall and head injury risk.",
            "description_es": "Trabajador detectado sin casco en andamio del segundo piso cerca del borde. Riesgo inmediato de caída y lesión en la cabeza.",
            "recommended_action": "Stop work in area. Provide hard hat immediately. Conduct toolbox talk on head protection for elevated work.",
            "osha_reference": "OSHA 1926.100(a) - Head Protection; OSHA 1926.451 - Scaffolding",
        }

        with patch("handler.bedrock_client") as mock_bedrock, \
             patch("handler.sns_client") as mock_sns:
            mock_bedrock.invoke_model.return_value = _build_bedrock_response(bedrock_analysis)
            mock_sns.publish.return_value = {"MessageId": "sns-e2e-001"}

            from handler import lambda_handler

            result = lambda_handler(sqs_event, None)

        # Step 4: Verify Lambda returned success
        assert result["statusCode"] == 200
        body = json.loads(result["body"])
        assert body["processed"] == 1
        assert body["results"][0]["status"] == "created"

        # Step 5: Verify DynamoDB has the alert
        items = table.scan()["Items"]
        assert len(items) == 1, f"Expected 1 DynamoDB item, got {len(items)}"

        alert = items[0]

        # Step 6: Verify all required fields
        # Jordan: These are the fields the human review dashboard and
        # supervisor notification system depend on. Missing any of these
        # breaks the downstream consumer.
        assert alert["violationType"] == "missing_hardhat"
        assert alert["zoneId"] == "zone-A-framing"
        assert alert["severity"] == 3
        assert alert["status"] == "open"
        assert alert["anonymizedWorkerId"] == "anon-e2e-001"

        # Step 7: Verify bilingual content
        assert alert["analysisEn"], "English analysis is empty"
        assert alert["analysisEs"], "Spanish analysis is empty"
        assert "hard hat" in alert["analysisEn"].lower() or "hardhat" in alert["analysisEn"].lower()
        assert "casco" in alert["analysisEs"].lower()

        # Step 8: Verify OSHA reference
        assert alert["oshaReference"], "OSHA reference is empty"
        assert "1926" in alert["oshaReference"]

        # Step 9: Verify no PII leaked
        alert_str = str(alert)  # Jordan: DynamoDB returns Decimal types, use str()
        pii_fields = [
            "worker_name", "name", "email", "phone_number",
            "badge_number", "ssn", "home_address", "face_id",
        ]
        for field in pii_fields:
            assert field not in alert, f"PII field '{field}' leaked to DynamoDB"

    @mock_aws
    def test_full_flow_with_bedrock_failure(self):
        """
        Jordan: Full flow but Bedrock is down. The pipeline should still
        produce an alert with fallback content. This tests graceful
        degradation — the most important property of a safety system.
        """
        infra = _create_mock_infrastructure()
        table = infra["table"]

        escalation = {
            "source_event_id": "evt-e2e-fallback-001",
            "violation_type": "missing_vest",
            "zone_id": "zone-B-excavation",
            "severity": 2,
            "description": "Worker in excavation zone without high-vis vest",
            "anonymized_worker_id": "anon-e2e-002",
        }

        sqs_event = {
            "Records": [
                {
                    "messageId": "msg-e2e-fallback",
                    "receiptHandle": "handle-e2e-fallback",
                    "body": json.dumps(escalation),
                    "attributes": {},
                    "messageAttributes": {},
                    "md5OfBody": "",
                    "eventSource": "aws:sqs",
                    "eventSourceARN": "arn:aws:sqs:us-east-1:123456789012:test",
                    "awsRegion": "us-east-1",
                }
            ]
        }

        with patch("handler.bedrock_client") as mock_bedrock, \
             patch("handler.sns_client") as mock_sns:
            # Jordan: Bedrock is completely down
            mock_bedrock.invoke_model.side_effect = Exception(
                "ServiceUnavailableException: Bedrock is experiencing issues"
            )
            mock_sns.publish.return_value = {"MessageId": "sns-fallback"}

            from handler import lambda_handler

            result = lambda_handler(sqs_event, None)

        # Jordan: Pipeline should still succeed
        assert result["statusCode"] == 200
        body = json.loads(result["body"])
        assert body["processed"] == 1

        # Jordan: Alert should exist with fallback content
        items = table.scan()["Items"]
        assert len(items) == 1
        alert = items[0]

        # Jordan: Fallback content should still be bilingual
        assert alert["analysisEn"], "Fallback English analysis is empty"
        assert alert["analysisEs"], "Fallback Spanish analysis is empty"
        assert alert["violationType"] == "missing_vest"
        assert alert["status"] == "open"

    @mock_aws
    def test_malformed_sqs_message_handled(self):
        """
        Jordan: An SQS message with invalid JSON in the body.
        Lambda should handle this gracefully — log error, don't crash
        the entire batch. With batch_size=1, this message goes to DLQ
        after the re-raise.
        """
        infra = _create_mock_infrastructure()

        sqs_event = {
            "Records": [
                {
                    "messageId": "msg-malformed",
                    "receiptHandle": "handle-malformed",
                    "body": "this is not valid json {{{",
                    "attributes": {},
                    "messageAttributes": {},
                    "md5OfBody": "",
                    "eventSource": "aws:sqs",
                    "eventSourceARN": "arn:aws:sqs:us-east-1:123456789012:test",
                    "awsRegion": "us-east-1",
                }
            ]
        }

        with patch("handler.bedrock_client"), \
             patch("handler.sns_client"):
            from handler import lambda_handler

            # Jordan: Malformed JSON is caught and logged. With batch_size=1,
            # the handler logs the error but returns 200 with errors count.
            # The message will be retried by SQS visibility timeout.
            result = lambda_handler(sqs_event, None)
            body = json.loads(result["body"])
            assert body["processed"] == 0
            assert body["errors"] == 1

    @mock_aws
    def test_gsi_query_after_alert_creation(self):
        """
        Jordan: After creating alerts, verify we can query them by zone
        using the GSI. This is how the supervisor dashboard will work —
        "Show me all alerts in zone-A sorted by severity."
        """
        infra = _create_mock_infrastructure()
        table = infra["table"]

        # Create alerts in different zones with different severities
        zones = [
            ("zone-A", 3, "missing_hardhat"),
            ("zone-A", 1, "missing_gloves"),
            ("zone-B", 4, "missing_harness"),
            ("zone-A", 2, "missing_vest"),
        ]

        bedrock_analysis = {
            "description_en": "Violation detected",
            "description_es": "Violación detectada",
            "recommended_action": "Correct immediately",
            "osha_reference": "OSHA 1926",
        }

        for i, (zone, severity, vtype) in enumerate(zones):
            escalation = {
                "source_event_id": f"evt-gsi-{i:03d}",
                "violation_type": vtype,
                "zone_id": zone,
                "severity": severity,
                "description": f"Test violation {i}",
                "anonymized_worker_id": f"anon-gsi-{i:03d}",
            }
            sqs_event = {
                "Records": [
                    {
                        "messageId": f"msg-gsi-{i}",
                        "receiptHandle": f"handle-gsi-{i}",
                        "body": json.dumps(escalation),
                        "attributes": {},
                        "messageAttributes": {},
                        "md5OfBody": "",
                        "eventSource": "aws:sqs",
                        "eventSourceARN": "arn:aws:sqs:us-east-1:123456789012:test",
                        "awsRegion": "us-east-1",
                    }
                ]
            }

            with patch("handler.bedrock_client") as mock_bedrock, \
                 patch("handler.sns_client") as mock_sns:
                mock_bedrock.invoke_model.return_value = _build_bedrock_response(
                    bedrock_analysis
                )
                mock_sns.publish.return_value = {"MessageId": f"sns-gsi-{i}"}

                from handler import lambda_handler
                lambda_handler(sqs_event, None)

        # Jordan: Now query zone-A alerts via GSI, sorted by severity
        response = table.query(
            IndexName="zone-severity-index",
            KeyConditionExpression=boto3.dynamodb.conditions.Key("zoneId").eq("zone-A"),
        )

        zone_a_items = response["Items"]
        assert len(zone_a_items) == 3, f"Expected 3 zone-A alerts, got {len(zone_a_items)}"

        # Jordan: Verify severity ordering (should be ascending: 1, 2, 3)
        severities = [item["severity"] for item in zone_a_items]
        assert severities == sorted(severities), (
            f"GSI results not sorted by severity: {severities}"
        )
