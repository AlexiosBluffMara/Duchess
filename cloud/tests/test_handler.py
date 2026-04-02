"""
Lambda Handler Unit Tests.

Jordan: These tests verify the PPE escalation Lambda handler's logic in
isolation. Every AWS service is mocked — Bedrock via unittest.mock (moto
doesn't support Bedrock), DynamoDB via moto. Each test targets a specific
behavior:

  - Valid event processing → DynamoDB write
  - Multiple record handling → all processed
  - Missing fields → graceful defaults
  - Bedrock failures → fallback analysis
  - PII stripping → no sensitive data in DynamoDB
  - Idempotency → no duplicate alerts
  - Bilingual output → both EN and ES populated

Cost of running: $0.00
Cost of deploying a broken handler: ~$0.03/invocation wasted on Bedrock +
potential safety incidents from dropped alerts.

Run: cd cloud && poetry run pytest tests/test_handler.py -v
"""

from __future__ import annotations

import json
from io import BytesIO
from unittest.mock import MagicMock, patch

import boto3
import pytest
from moto import mock_aws


# ── Helpers ─────────────────────────────────────────────────────────────────


def _make_sqs_event(*escalations: dict) -> dict:
    """
    Jordan: Helper to build SQS events from escalation payloads.
    Saves 10 lines of boilerplate per test.
    """
    return {
        "Records": [
            {
                "messageId": f"msg-{i}",
                "receiptHandle": f"handle-{i}",
                "body": json.dumps(esc),
                "attributes": {},
                "messageAttributes": {},
                "md5OfBody": "",
                "eventSource": "aws:sqs",
                "eventSourceARN": "arn:aws:sqs:us-east-1:123456789012:test",
                "awsRegion": "us-east-1",
            }
            for i, esc in enumerate(escalations)
        ]
    }


def _mock_bedrock_response(analysis: dict) -> MagicMock:
    """
    Jordan: Build a mock Bedrock response object that mimics the real
    invoke_model return structure. The body is a streaming object with
    a read() method that returns JSON bytes.
    """
    response_body = {
        "content": [{"type": "text", "text": json.dumps(analysis)}],
        "model": "claude-3-5-sonnet",
        "stop_reason": "end_turn",
    }
    mock_response = {
        "body": BytesIO(json.dumps(response_body).encode()),
        "contentType": "application/json",
    }
    return mock_response


# ── Test: Single Escalation Processing ──────────────────────────────────────


class TestProcessSingleEscalation:
    """Jordan: The happy path. One SQS message → one DynamoDB alert."""

    @mock_aws
    def test_process_single_escalation(self, sample_escalation, mock_bedrock_response):
        """
        Jordan: Valid escalation event → Bedrock analysis → DynamoDB write.
        This is the core flow. If this test fails, the entire pipeline is broken.
        """
        # Setup: Create DynamoDB table
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
            ],
            BillingMode="PAY_PER_REQUEST",
        )
        table.meta.client.get_waiter("table_exists").wait(
            TableName="duchess-alerts-test"
        )

        # Mock Bedrock
        mock_bedrock = _mock_bedrock_response(mock_bedrock_response)

        with patch("handler.bedrock_client") as mock_client, \
             patch("handler.sns_client") as mock_sns:
            mock_client.invoke_model.return_value = mock_bedrock
            mock_sns.publish.return_value = {"MessageId": "test-msg-id"}

            # Import handler AFTER patching (module-level clients)
            from handler import lambda_handler

            event = _make_sqs_event(sample_escalation)
            result = lambda_handler(event, None)

            body = json.loads(result["body"])
            assert result["statusCode"] == 200
            assert body["processed"] == 1
            assert body["results"][0]["status"] == "created"

            # Jordan: Verify the alert was written to DynamoDB
            items = table.scan()["Items"]
            assert len(items) == 1
            assert items[0]["violationType"] == "missing_hardhat"
            assert items[0]["zoneId"] == "zone-A-framing"
            assert items[0]["severity"] == 3


class TestProcessMultipleRecords:
    """Jordan: SQS can deliver multiple records (if batch_size > 1)."""

    @mock_aws
    def test_process_multiple_records(self, mock_bedrock_response):
        """
        Jordan: Three different violations in one event → three DynamoDB items.
        Each record is processed independently.
        """
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
            ],
            BillingMode="PAY_PER_REQUEST",
        )
        table.meta.client.get_waiter("table_exists").wait(
            TableName="duchess-alerts-test"
        )

        escalations = [
            {
                "source_event_id": f"evt-batch-{i}",
                "violation_type": vtype,
                "zone_id": f"zone-{chr(65 + i)}",
                "severity": i + 1,
                "description": f"Test violation {i}",
                "anonymized_worker_id": f"anon-{i}",
            }
            for i, vtype in enumerate(["missing_hardhat", "missing_vest", "missing_gloves"])
        ]

        with patch("handler.bedrock_client") as mock_client, \
             patch("handler.sns_client") as mock_sns:
            mock_client.invoke_model.return_value = _mock_bedrock_response(
                mock_bedrock_response
            )
            mock_sns.publish.return_value = {"MessageId": "test"}

            from handler import lambda_handler

            event = _make_sqs_event(*escalations)
            result = lambda_handler(event, None)

            body = json.loads(result["body"])
            assert body["processed"] == 3

            items = table.scan()["Items"]
            assert len(items) == 3


# ── Test: Missing Fields ────────────────────────────────────────────────────


class TestMissingFields:
    """Jordan: Real-world payloads are messy. Handle missing fields gracefully."""

    @mock_aws
    def test_missing_violation_type_uses_default(self, mock_bedrock_response):
        """
        Jordan: If the phone app sends a payload without violation_type,
        we default to 'unknown' rather than crashing. The alert still gets
        created — better to have an 'unknown' alert than no alert at all.
        """
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
            ],
            BillingMode="PAY_PER_REQUEST",
        )
        table.meta.client.get_waiter("table_exists").wait(
            TableName="duchess-alerts-test"
        )

        # Jordan: Minimal payload — just zone_id, nothing else
        escalation = {
            "source_event_id": "evt-minimal-001",
            "zone_id": "zone-B",
        }

        with patch("handler.bedrock_client") as mock_client, \
             patch("handler.sns_client") as mock_sns:
            mock_client.invoke_model.return_value = _mock_bedrock_response(
                mock_bedrock_response
            )
            mock_sns.publish.return_value = {"MessageId": "test"}

            from handler import lambda_handler

            event = _make_sqs_event(escalation)
            result = lambda_handler(event, None)

            body = json.loads(result["body"])
            assert body["processed"] == 1

            items = table.scan()["Items"]
            assert len(items) == 1
            # Jordan: Should default to "unknown" for missing violation_type
            assert items[0]["violationType"] == "unknown"
            assert items[0]["severity"] == 1  # Default severity


# ── Test: Bedrock Failure Fallback ──────────────────────────────────────────


class TestBedrockFailure:
    """Jordan: Bedrock WILL go down. When it does, we fall back to templates."""

    @mock_aws
    def test_bedrock_failure_uses_fallback(self):
        """
        Jordan: Bedrock throws an exception → handler uses fallback analysis.
        The alert still gets created with template-based bilingual content.
        This is the safety net — we can't let Bedrock downtime block safety alerts.
        """
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
            ],
            BillingMode="PAY_PER_REQUEST",
        )
        table.meta.client.get_waiter("table_exists").wait(
            TableName="duchess-alerts-test"
        )

        escalation = {
            "source_event_id": "evt-bedrock-fail-001",
            "violation_type": "missing_hardhat",
            "zone_id": "zone-C",
            "severity": 3,
            "description": "Worker on scaffolding",
            "anonymized_worker_id": "anon-fail-001",
        }

        with patch("handler.bedrock_client") as mock_client, \
             patch("handler.sns_client") as mock_sns:
            # Jordan: Simulate Bedrock failure
            mock_client.invoke_model.side_effect = Exception("Bedrock is down!")
            mock_sns.publish.return_value = {"MessageId": "test"}

            from handler import lambda_handler

            event = _make_sqs_event(escalation)
            result = lambda_handler(event, None)

            body = json.loads(result["body"])
            assert body["processed"] == 1

            items = table.scan()["Items"]
            assert len(items) == 1
            # Jordan: Fallback should still produce bilingual content
            item = items[0]
            assert item["analysisEn"] != ""
            assert item["analysisEs"] != ""
            assert "hardhat" in item["analysisEn"].lower() or "hard hat" in item["analysisEn"].lower()
            assert "casco" in item["analysisEs"].lower()


# ── Test: No PII in DynamoDB ───────────────────────────────────────────────


class TestNoPiiInDynamodb:
    """
    Jordan: This is the most important test in the entire suite. If PII
    leaks to DynamoDB, we violate HIPAA, project privacy rules, AND
    potentially union contracts. This is a fireable offense in production.
    """

    @mock_aws
    def test_no_pii_in_dynamodb_item(self, sample_escalation_with_pii, mock_bedrock_response):
        """
        Jordan: Send a payload with PII fields → verify NONE of them
        appear in the DynamoDB item. The handler MUST strip all PII
        before writing.
        """
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
            ],
            BillingMode="PAY_PER_REQUEST",
        )
        table.meta.client.get_waiter("table_exists").wait(
            TableName="duchess-alerts-test"
        )

        with patch("handler.bedrock_client") as mock_client, \
             patch("handler.sns_client") as mock_sns:
            mock_client.invoke_model.return_value = _mock_bedrock_response(
                mock_bedrock_response
            )
            mock_sns.publish.return_value = {"MessageId": "test"}

            from handler import lambda_handler

            event = _make_sqs_event(sample_escalation_with_pii)
            result = lambda_handler(event, None)

            items = table.scan()["Items"]
            assert len(items) == 1
            item = items[0]

            # Jordan: These fields MUST NOT exist in the DynamoDB item
            pii_fields = [
                "worker_name", "name", "email", "phone_number",
                "badge_number", "exact_gps", "gps_lat", "gps_lon",
                "face_id", "ssn", "home_address",
            ]
            for field in pii_fields:
                assert field not in item, f"PII field '{field}' found in DynamoDB item!"

            # Jordan: Also check that PII VALUES don't appear in string fields
            pii_values = ["John Doe", "jdoe@example.com", "555-0123", "B-9876"]
            item_str = str(item)  # Jordan: DynamoDB returns Decimal, use str() not json.dumps()
            for value in pii_values:
                assert value not in item_str, f"PII value '{value}' found in DynamoDB item!"


# ── Test: Empty Records ────────────────────────────────────────────────────


class TestEmptyRecords:
    """Jordan: Edge case — Lambda invoked with no records."""

    def test_empty_records_list(self):
        """
        Jordan: An event with an empty Records list should return 200
        with 0 processed, not crash. This can happen if SQS delivers
        an empty batch (rare but documented).
        """
        from handler import lambda_handler

        result = lambda_handler({"Records": []}, None)

        body = json.loads(result["body"])
        assert result["statusCode"] == 200
        assert body["processed"] == 0

    def test_no_records_key(self):
        """
        Jordan: An event with no 'Records' key at all. Even more defensive.
        """
        from handler import lambda_handler

        result = lambda_handler({}, None)

        body = json.loads(result["body"])
        assert result["statusCode"] == 200
        assert body["processed"] == 0


# ── Test: Idempotency ──────────────────────────────────────────────────────


class TestIdempotency:
    """
    Jordan: SQS at-least-once delivery means we WILL receive duplicates.
    The handler must detect and skip them to avoid:
      1. Duplicate alerts in DynamoDB (confuses supervisors)
      2. Duplicate Bedrock calls (wastes $0.03 each)
      3. Duplicate SNS notifications (alert fatigue)
    """

    @mock_aws
    def test_idempotency_same_event_twice_creates_one_alert(self, mock_bedrock_response):
        """
        Jordan: Process the same escalation twice → only one DynamoDB item.
        The second invocation should detect the duplicate and skip.
        """
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
            ],
            BillingMode="PAY_PER_REQUEST",
        )
        table.meta.client.get_waiter("table_exists").wait(
            TableName="duchess-alerts-test"
        )

        escalation = {
            "source_event_id": "evt-idempotent-001",
            "violation_type": "missing_vest",
            "zone_id": "zone-D",
            "severity": 2,
            "description": "Worker without high-vis vest",
            "anonymized_worker_id": "anon-idem-001",
        }

        with patch("handler.bedrock_client") as mock_client, \
             patch("handler.sns_client") as mock_sns:
            mock_client.invoke_model.return_value = _mock_bedrock_response(
                mock_bedrock_response
            )
            mock_sns.publish.return_value = {"MessageId": "test"}

            from handler import lambda_handler

            # First invocation
            event = _make_sqs_event(escalation)
            result1 = lambda_handler(event, None)
            body1 = json.loads(result1["body"])
            assert body1["processed"] == 1
            assert body1["results"][0]["status"] == "created"

            # Second invocation with SAME event
            # Jordan: Reset the Bedrock mock's return value (BytesIO needs reset)
            mock_client.invoke_model.return_value = _mock_bedrock_response(
                mock_bedrock_response
            )
            result2 = lambda_handler(event, None)
            body2 = json.loads(result2["body"])
            assert body2["processed"] == 1
            assert body2["results"][0]["status"] == "duplicate_skipped"

            # Jordan: Only ONE item in DynamoDB, not two
            items = table.scan()["Items"]
            assert len(items) == 1


# ── Test: Bilingual Output ─────────────────────────────────────────────────


class TestBilingualOutput:
    """
    Jordan: Bilingual alerts are non-negotiable. Every construction site in
    our target market has Spanish-speaking workers. An English-only alert
    is useless to half the workforce.
    """

    @mock_aws
    def test_bilingual_output_both_languages_populated(self, mock_bedrock_response):
        """
        Jordan: Verify that both analysisEn and analysisEs fields are
        populated in the DynamoDB item. Empty strings are failures.
        """
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
            ],
            BillingMode="PAY_PER_REQUEST",
        )
        table.meta.client.get_waiter("table_exists").wait(
            TableName="duchess-alerts-test"
        )

        escalation = {
            "source_event_id": "evt-bilingual-001",
            "violation_type": "missing_hardhat",
            "zone_id": "zone-E",
            "severity": 3,
            "description": "Worker on 3rd floor without hard hat",
            "anonymized_worker_id": "anon-bil-001",
        }

        with patch("handler.bedrock_client") as mock_client, \
             patch("handler.sns_client") as mock_sns:
            mock_client.invoke_model.return_value = _mock_bedrock_response(
                mock_bedrock_response
            )
            mock_sns.publish.return_value = {"MessageId": "test"}

            from handler import lambda_handler

            event = _make_sqs_event(escalation)
            result = lambda_handler(event, None)

            items = table.scan()["Items"]
            assert len(items) == 1
            item = items[0]

            # Jordan: Both language fields must be non-empty
            assert item["analysisEn"], "English analysis is empty!"
            assert item["analysisEs"], "Spanish analysis is empty!"

            # Jordan: English should contain English words
            assert any(
                word in item["analysisEn"].lower()
                for word in ["worker", "hard hat", "detected", "hat", "hardhat", "head"]
            ), f"English analysis doesn't look English: {item['analysisEn']}"

            # Jordan: Spanish should contain Spanish words
            assert any(
                word in item["analysisEs"].lower()
                for word in ["trabajador", "casco", "detectado", "zona", "protección"]
            ), f"Spanish analysis doesn't look Spanish: {item['analysisEs']}"


# ── Test: Input Sanitization ───────────────────────────────────────────────


class TestInputSanitization:
    """Jordan: Never trust input from upstream systems."""

    def test_severity_clamped_to_valid_range(self):
        """
        Jordan: Severity must be 1-4. Values outside this range should
        be clamped, not passed through. A severity of 999 would break
        downstream alert routing.
        """
        from handler import _sanitize_escalation

        # Too high
        result = _sanitize_escalation({"severity": 999, "violation_type": "missing_hardhat"})
        assert result["severity"] == 4

        # Too low
        result = _sanitize_escalation({"severity": -5, "violation_type": "missing_hardhat"})
        assert result["severity"] == 1

        # Non-numeric
        result = _sanitize_escalation({"severity": "high", "violation_type": "missing_hardhat"})
        assert result["severity"] == 1

    def test_unknown_violation_type_defaults(self):
        """
        Jordan: Unknown violation types get replaced with 'unknown'.
        We don't pass arbitrary strings to Bedrock prompts or DynamoDB.
        """
        from handler import _sanitize_escalation

        result = _sanitize_escalation({"violation_type": "made_up_type"})
        assert result["violation_type"] == "unknown"

    def test_description_truncated(self):
        """
        Jordan: Descriptions over 2000 chars are truncated. This prevents
        prompt injection via overly long descriptions and controls Bedrock
        token costs.
        """
        from handler import _sanitize_escalation

        long_desc = "A" * 5000
        result = _sanitize_escalation({
            "description": long_desc,
            "violation_type": "missing_hardhat",
        })
        assert len(result["description"]) == 2000
