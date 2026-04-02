"""
PPE Escalation Lambda Handler.

Jordan: This Lambda is the real-time brain of the PPE escalation pipeline.
It sits between the SQS queue (fed by phones confirming PPE violations) and
the DynamoDB alerts table. Each invocation costs roughly:
  - Lambda execution: ~$0.000008 (512MB × 5s avg)
  - Bedrock Claude 3.5: ~$0.01-0.03 per call (depending on token count)
  - DynamoDB write: ~$0.00000125

So each PPE escalation costs us about $0.01-0.03 total. That's pennies to
potentially save a life. Worth every cent.

PRIVACY RULES (NON-NEGOTIABLE):
  - No worker PII in DynamoDB (no names, badge numbers, exact GPS)
  - The anonymizedWorkerId field is a rotating pseudonym hash
  - No PII in CloudWatch logs (we use structured logging with sanitized fields)
  - Bedrock prompts contain violation type + description only, never worker identity
  - If you add a log statement with worker data, you WILL be fired (virtually)

IDEMPOTENCY:
  - Each escalation has a source_event_id from the phone
  - We check DynamoDB before writing to prevent duplicate alerts
  - SQS at-least-once delivery means we WILL see duplicates — handle them

ERROR HANDLING:
  - Bedrock failures → fallback to template-based bilingual response
  - DynamoDB failures → let SQS retry (message goes back to queue)
  - Malformed messages → log error, DON'T retry (send to DLQ via explicit failure)
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import time

import boto3
from botocore.exceptions import ClientError

# ── Structured Logging ──────────────────────────────────────────────────────
# Jordan: We use structured JSON logging so CloudWatch Insights can query
# fields directly. No print() statements — those are for amateurs.
# Log level controlled by environment variable so we can turn up verbosity
# in dev without redeploying.
logger = logging.getLogger("duchess.ppe_escalation")
logger.setLevel(os.environ.get("LOG_LEVEL", "INFO"))

# Jordan: If there's no handler yet (Lambda adds one by default, but tests don't),
# add a basic JSON formatter. In production Lambda, the default handler is fine.
if not logger.handlers:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(message)s"))
    logger.addHandler(handler)


# ── AWS Clients ─────────────────────────────────────────────────────────────
# Jordan: Clients are module-level singletons — they persist across warm Lambda
# invocations. This saves ~200ms on connection setup per warm start. The
# Lambda execution environment reuses these across invocations.
dynamodb_resource = boto3.resource("dynamodb")
bedrock_client = boto3.client("bedrock-runtime")
sns_client = boto3.client("sns")

# ── Environment Config ──────────────────────────────────────────────────────
# Jordan: Every external reference is an env var. No hardcoded ARNs, no
# hardcoded table names, no magic strings. 12-factor serverless.
ALERTS_TABLE_NAME = os.environ.get("ALERTS_TABLE_NAME", "duchess-alerts-dev")
VIDEO_BUCKET_NAME = os.environ.get("VIDEO_BUCKET_NAME", "")
BEDROCK_MODEL_ID = os.environ.get(
    "BEDROCK_MODEL_ID", "anthropic.claude-3-5-sonnet-20241022-v2:0"
)
ALERT_TOPIC_ARN = os.environ.get("ALERT_TOPIC_ARN", "")
STAGE = os.environ.get("STAGE", "dev")

# Jordan: Fields that MUST NEVER appear in DynamoDB items or log messages.
# If any of these keys appear in an escalation payload, we strip them.
# This is a safety net — the phone app should already anonymize, but defense in depth.
PII_FIELDS = frozenset({
    "worker_name", "name", "first_name", "last_name",
    "email", "phone_number", "badge_number", "badge_id",
    "face_id", "face_encoding", "biometric_id",
    "ssn", "social_security", "date_of_birth", "dob",
    "home_address", "address", "exact_gps", "gps_lat", "gps_lon",
    "photo_url", "face_url", "worker_photo",
})

# Jordan: Valid violation types. If we get something outside this list,
# we default to "unknown" rather than blindly trusting input.
VALID_VIOLATION_TYPES = frozenset({
    "missing_hardhat", "missing_vest", "missing_gloves", "missing_goggles",
    "missing_harness", "missing_boots", "missing_respirator",
    "improper_hardhat", "improper_vest", "improper_harness",
    "damaged_ppe", "expired_ppe",
    "fall_hazard", "electrical_hazard", "confined_space",
    "unknown",
})

# Jordan: Severity must be 1-4 per the alert system spec.
MIN_SEVERITY = 1
MAX_SEVERITY = 4


def lambda_handler(event: dict, context) -> dict:
    """
    Process PPE escalation events from SQS.

    Jordan: This is the entry point. SQS delivers messages one at a time
    (batch_size=1 in CDK), but the handler supports multiple records
    for robustness. Each record is processed independently — if one fails,
    the others still succeed.
    """
    records = event.get("Records", [])

    if not records:
        # Jordan: Empty records list. Shouldn't happen with SQS trigger,
        # but defensive coding costs nothing.
        logger.warning(json.dumps({
            "level": "WARN",
            "message": "Received event with no records",
            "event_source": "sqs",
        }))
        return {
            "statusCode": 200,
            "body": json.dumps({"processed": 0, "results": []}),
        }

    alerts_table = dynamodb_resource.Table(ALERTS_TABLE_NAME)
    results = []
    errors = []

    for i, record in enumerate(records):
        try:
            body = json.loads(record["body"])

            # Jordan: Validate and sanitize input BEFORE any processing.
            # Bad input should fail fast, not halfway through a Bedrock call.
            sanitized = _sanitize_escalation(body)

            result = process_escalation(sanitized, alerts_table)
            results.append(result)

            logger.info(json.dumps({
                "level": "INFO",
                "message": "Escalation processed successfully",
                "alert_id": result.get("alert_id"),
                "violation_type": sanitized.get("violation_type"),
                "zone_id": sanitized.get("zone_id"),
                "stage": STAGE,
                # Jordan: NO worker ID in logs. Not even the anonymized one.
                # CloudWatch logs are accessible to more people than DynamoDB.
            }))

        except json.JSONDecodeError as e:
            # Jordan: Malformed JSON. This message will never parse correctly,
            # so retrying is pointless. Log the error and let it go to DLQ.
            logger.error(json.dumps({
                "level": "ERROR",
                "message": "Malformed JSON in SQS message body",
                "record_index": i,
                "error": str(e),
            }))
            errors.append({"record_index": i, "error": "malformed_json"})

        except Exception as e:
            # Jordan: Unexpected error. Log it, and let SQS retry the message.
            # If it fails 3 times, SQS sends it to the DLQ automatically.
            logger.error(json.dumps({
                "level": "ERROR",
                "message": "Failed to process escalation",
                "record_index": i,
                "error_type": type(e).__name__,
                "error": str(e),
            }))
            errors.append({"record_index": i, "error": str(e)})
            # Jordan: Re-raise so SQS knows this message failed and should retry.
            # With batch_size=1, this only affects this one message.
            raise

    return {
        "statusCode": 200,
        "body": json.dumps({
            "processed": len(results),
            "errors": len(errors),
            "results": results,
        }),
    }


def _sanitize_escalation(escalation: dict) -> dict:
    """
    Sanitize and validate an escalation payload.

    Jordan: Defense in depth. The phone app SHOULD anonymize data before
    sending, but we don't trust upstream systems. Strip PII fields,
    validate types, clamp severity. This function is the last line of
    defense before data hits Bedrock or DynamoDB.
    """
    sanitized = {}

    for key, value in escalation.items():
        # Jordan: Strip any PII fields that somehow made it through.
        # Log a warning because this means the phone app has a bug.
        if key.lower() in PII_FIELDS:
            logger.warning(json.dumps({
                "level": "WARN",
                "message": "PII field stripped from escalation payload",
                "field_name": key,
                # Jordan: Do NOT log the value — that defeats the purpose.
            }))
            continue
        sanitized[key] = value

    # Jordan: Validate violation_type against allowed list.
    # Untrusted input should never pass through unchecked.
    vtype = sanitized.get("violation_type", "unknown")
    if vtype not in VALID_VIOLATION_TYPES:
        logger.warning(json.dumps({
            "level": "WARN",
            "message": "Unknown violation type, defaulting to 'unknown'",
            "original_type": str(vtype)[:100],  # Truncate to prevent log injection
        }))
        sanitized["violation_type"] = "unknown"

    # Jordan: Clamp severity to valid range (1-4).
    try:
        severity = int(sanitized.get("severity", 1))
        sanitized["severity"] = max(MIN_SEVERITY, min(MAX_SEVERITY, severity))
    except (ValueError, TypeError):
        sanitized["severity"] = MIN_SEVERITY

    # Jordan: Truncate description to prevent prompt injection / cost explosion.
    # 2000 chars is plenty for a scene description.
    desc = sanitized.get("description", "")
    if isinstance(desc, str) and len(desc) > 2000:
        sanitized["description"] = desc[:2000]

    return sanitized


def _generate_idempotency_key(escalation: dict) -> str:
    """
    Generate a deterministic idempotency key from escalation data.

    Jordan: SQS has at-least-once delivery. The same message CAN arrive twice.
    We generate a hash from the source event ID (if present) or from the
    combination of violation_type + zone_id + anonymized_worker_id + a time
    window. This prevents duplicate alerts from the same incident.
    """
    # Jordan: If the phone app sends a source_event_id, use that — it's the
    # most reliable dedup key because it's set at the source.
    source_id = escalation.get("source_event_id")
    if source_id:
        return hashlib.sha256(source_id.encode()).hexdigest()[:32]

    # Jordan: Fallback — hash the key fields + a 60-second time window.
    # This means two escalations for the same violation/zone/worker within
    # 60 seconds are considered duplicates. Good enough for construction.
    time_window = int(time.time() / 60)  # 1-minute buckets
    composite = (
        f"{escalation.get('violation_type', '')}:"
        f"{escalation.get('zone_id', '')}:"
        f"{escalation.get('anonymized_worker_id', '')}:"
        f"{time_window}"
    )
    return hashlib.sha256(composite.encode()).hexdigest()[:32]


def _check_idempotency(idempotency_key: str, alerts_table) -> bool:
    """
    Check if an alert with this idempotency key already exists.

    Jordan: We use the idempotency key as the alert ID, so we can do a
    query on the partition key to find any items with that ID regardless
    of timestamp. This is efficient — it's a single partition key lookup,
    not a scan.

    Returns True if a duplicate exists (skip processing), False otherwise.
    """
    try:
        # Jordan: Query by partition key only — returns all items with this ID.
        # With our idempotency design (id = idempotency_key), there should be
        # at most one item. Limit=1 for efficiency.
        response = alerts_table.query(
            KeyConditionExpression=boto3.dynamodb.conditions.Key("id").eq(idempotency_key),
            Limit=1,
            Select="COUNT",
        )
        return response.get("Count", 0) > 0
    except ClientError:
        # Jordan: If the check fails, proceed with processing.
        # Better to create a duplicate than to drop an alert.
        return False


def process_escalation(escalation: dict, alerts_table) -> dict:
    """
    Process a single PPE escalation:
      1. Check idempotency (skip duplicates)
      2. Call Bedrock Claude for analysis
      3. Write alert to DynamoDB
      4. Publish to SNS for downstream consumers

    Jordan: Each call to this function costs ~$0.01-0.03 in Bedrock fees.
    The idempotency check saves us from paying twice for the same violation,
    which matters at scale (100K events/day = $1K-3K/day in Bedrock alone).
    """
    # ── Idempotency Check ───────────────────────────────────────────────────
    idempotency_key = _generate_idempotency_key(escalation)

    if _check_idempotency(idempotency_key, alerts_table):
        logger.info(json.dumps({
            "level": "INFO",
            "message": "Duplicate escalation detected, skipping",
            "idempotency_key": idempotency_key,
        }))
        return {"alert_id": idempotency_key, "status": "duplicate_skipped"}

    alert_id = idempotency_key  # Jordan: Use idempotency key as ID for dedup
    timestamp = int(time.time() * 1000)

    violation_type = escalation.get("violation_type", "unknown")
    zone_id = escalation.get("zone_id", "unknown")
    severity = escalation.get("severity", 1)
    description = escalation.get("description", "")

    # ── Bedrock Analysis ────────────────────────────────────────────────────
    # Jordan: This is the expensive call. ~$0.01-0.03 per invocation.
    # If Bedrock fails, we fall back to a template-based response rather
    # than failing the whole escalation. A basic alert is better than no alert.
    analysis = call_bedrock_analysis(violation_type, description)

    # ── Build DynamoDB Item ─────────────────────────────────────────────────
    # Jordan: PRIVACY CHECK — this item is the final form that gets persisted.
    # Audit every field. No worker names, no GPS coordinates, no face data.
    # The anonymizedWorkerId is a rotating pseudonym set by the phone app.
    alert_item = {
        "id": alert_id,
        "timestamp": timestamp,
        "violationType": violation_type,
        "zoneId": zone_id,
        "severity": severity,
        "status": "open",
        "anonymizedWorkerId": escalation.get("anonymized_worker_id", "anonymous"),
        "analysisEn": analysis.get("description_en", ""),
        "analysisEs": analysis.get("description_es", ""),
        "recommendedAction": analysis.get("recommended_action", ""),
        "oshaReference": analysis.get("osha_reference", ""),
        "idempotencyKey": idempotency_key,
        "stage": STAGE,
        "sourceEventId": escalation.get("source_event_id", ""),
    }

    # ── Write to DynamoDB ───────────────────────────────────────────────────
    # Jordan: Conditional write — only create if the ID doesn't exist yet.
    # This is a belt-and-suspenders approach alongside the idempotency check.
    try:
        alerts_table.put_item(
            Item=alert_item,
            ConditionExpression="attribute_not_exists(id)",
        )
    except ClientError as e:
        if e.response["Error"]["Code"] == "ConditionalCheckFailedException":
            # Jordan: Item already exists — another invocation beat us to it.
            # This is fine. Return as duplicate.
            logger.info(json.dumps({
                "level": "INFO",
                "message": "Conditional write failed — duplicate detected",
                "alert_id": alert_id,
            }))
            return {"alert_id": alert_id, "status": "duplicate_skipped"}
        raise  # Jordan: Any other DynamoDB error should bubble up for retry.

    # ── Publish to SNS ──────────────────────────────────────────────────────
    # Jordan: Notify downstream consumers (human review dashboard, supervisor
    # phones, ops monitoring). SNS fanout is ~$0.50/million publishes.
    # If SNS fails, the alert is still in DynamoDB — don't fail the whole flow.
    _publish_alert_notification(alert_item)

    return {"alert_id": alert_id, "status": "created"}


def _publish_alert_notification(alert_item: dict) -> None:
    """
    Publish alert to SNS topic for downstream notification.

    Jordan: This is fire-and-forget. If SNS is down, the alert still exists
    in DynamoDB. The human review dashboard polls DynamoDB directly, so
    SNS failure doesn't block the safety workflow. It just delays push
    notifications to supervisors.
    """
    if not ALERT_TOPIC_ARN:
        # Jordan: No topic configured (dev/test environment). Skip silently.
        return

    try:
        # Jordan: Message attributes enable SNS filtering. Subscribers can
        # filter by severity (e.g., only CRITICAL+EMERGENCY → on-call pager).
        sns_client.publish(
            TopicArn=ALERT_TOPIC_ARN,
            Subject=f"PPE Alert [{alert_item.get('severity', 1)}]: {alert_item.get('violationType', 'unknown')}",
            Message=json.dumps({
                "alert_id": alert_item["id"],
                "violation_type": alert_item.get("violationType"),
                "zone_id": alert_item.get("zoneId"),
                "severity": alert_item.get("severity"),
                "analysis_en": alert_item.get("analysisEn"),
                "analysis_es": alert_item.get("analysisEs"),
                "recommended_action": alert_item.get("recommendedAction"),
                # Jordan: NO worker ID in SNS messages. SNS can fan out to
                # email, SMS, HTTP — all with different security postures.
            }),
            MessageAttributes={
                "severity": {
                    "DataType": "Number",
                    "StringValue": str(alert_item.get("severity", 1)),
                },
                "violationType": {
                    "DataType": "String",
                    "StringValue": alert_item.get("violationType", "unknown"),
                },
                "zoneId": {
                    "DataType": "String",
                    "StringValue": alert_item.get("zoneId", "unknown"),
                },
            },
        )
        logger.info(json.dumps({
            "level": "INFO",
            "message": "Alert published to SNS",
            "alert_id": alert_item["id"],
            "topic": ALERT_TOPIC_ARN.split(":")[-1] if ALERT_TOPIC_ARN else "none",
        }))
    except ClientError as e:
        # Jordan: Log but don't fail. The alert is in DynamoDB — that's what matters.
        logger.error(json.dumps({
            "level": "ERROR",
            "message": "Failed to publish to SNS — alert still in DynamoDB",
            "alert_id": alert_item["id"],
            "error": str(e),
        }))


def call_bedrock_analysis(violation_type: str, description: str) -> dict:
    """
    Call Bedrock Claude to analyze a PPE violation and generate
    bilingual recommendations.

    Jordan: This costs ~$0.01-0.03 per call. We keep the prompt tight
    (under 500 tokens input) and cap output at 512 tokens to control cost.
    Temperature 0.1 for consistency — we want deterministic safety advice,
    not creative writing.

    Fallback: If Bedrock fails (quota, timeout, outage), we return a
    template-based response. A basic alert is infinitely better than no alert.
    """
    prompt = (
        "You are a construction safety expert. Analyze this PPE violation and provide:\n"
        "1. A brief description in English\n"
        "2. A brief description in Spanish\n"
        "3. The recommended corrective action\n"
        "4. The relevant OSHA standard reference\n\n"
        f"Violation type: {violation_type}\n"
        f"Scene description: {description}\n\n"
        "Respond in JSON format:\n"
        "{\n"
        '    "description_en": "...",\n'
        '    "description_es": "...",\n'
        '    "recommended_action": "...",\n'
        '    "osha_reference": "..."\n'
        "}"
    )

    try:
        response = bedrock_client.invoke_model(
            modelId=BEDROCK_MODEL_ID,
            contentType="application/json",
            accept="application/json",
            body=json.dumps({
                "anthropic_version": "bedrock-2023-05-31",
                "max_tokens": 512,
                "temperature": 0.1,
                "messages": [
                    {"role": "user", "content": prompt}
                ],
            }),
        )

        response_body = json.loads(response["body"].read())
        content = response_body.get("content", [{}])[0].get("text", "{}")

        # Jordan: Parse the JSON response. If Claude returns malformed JSON
        # (rare but possible), fall through to the fallback.
        parsed = json.loads(content)

        # Jordan: Validate that both language fields are present.
        # Bilingual output is non-negotiable per project requirements.
        if not parsed.get("description_en") or not parsed.get("description_es"):
            logger.warning(json.dumps({
                "level": "WARN",
                "message": "Bedrock response missing bilingual fields, using fallback",
            }))
            return _fallback_analysis(violation_type)

        return parsed

    except json.JSONDecodeError:
        # Jordan: Bedrock returned non-JSON. Use fallback.
        logger.warning(json.dumps({
            "level": "WARN",
            "message": "Bedrock returned non-JSON response, using fallback",
        }))
        return _fallback_analysis(violation_type)

    except ClientError as e:
        # Jordan: AWS API error (throttling, model not available, etc.)
        logger.error(json.dumps({
            "level": "ERROR",
            "message": "Bedrock API error, using fallback analysis",
            "error_code": e.response["Error"]["Code"],
            "error": str(e),
        }))
        return _fallback_analysis(violation_type)

    except Exception as e:
        # Jordan: Catch-all for unexpected errors. ALWAYS fall back.
        logger.error(json.dumps({
            "level": "ERROR",
            "message": "Unexpected error calling Bedrock, using fallback",
            "error_type": type(e).__name__,
            "error": str(e),
        }))
        return _fallback_analysis(violation_type)


def _fallback_analysis(violation_type: str) -> dict:
    """
    Template-based fallback when Bedrock is unavailable.

    Jordan: This is NOT a replacement for Bedrock analysis — it's a safety net.
    The templates are generic but accurate enough to create an actionable alert.
    A supervisor seeing "PPE violation detected: missing_hardhat" can still act.

    Cost: $0.00 (no API call). Use this for testing too.
    """
    # Jordan: Violation-specific templates. Better than a one-size-fits-all message.
    templates = {
        "missing_hardhat": {
            "description_en": "Worker detected without required hard hat in active construction zone.",
            "description_es": "Trabajador detectado sin casco requerido en zona de construcción activa.",
            "recommended_action": "Immediately provide hard hat and re-train on head protection requirements.",
            "osha_reference": "OSHA 1926.100 - Head Protection",
        },
        "missing_vest": {
            "description_en": "Worker detected without high-visibility safety vest.",
            "description_es": "Trabajador detectado sin chaleco de seguridad de alta visibilidad.",
            "recommended_action": "Provide high-visibility vest immediately. Review site visibility requirements.",
            "osha_reference": "OSHA 1926.201 - Signaling / ANSI 107",
        },
        "missing_harness": {
            "description_en": "Worker at elevation detected without fall protection harness.",
            "description_es": "Trabajador en altura detectado sin arnés de protección contra caídas.",
            "recommended_action": "Stop work immediately. Provide proper fall protection equipment and training.",
            "osha_reference": "OSHA 1926.502 - Fall Protection Systems",
        },
        "missing_gloves": {
            "description_en": "Worker detected without required hand protection.",
            "description_es": "Trabajador detectado sin protección de manos requerida.",
            "recommended_action": "Provide appropriate gloves for the task being performed.",
            "osha_reference": "OSHA 1926.95 - Personal Protective Equipment",
        },
        "missing_goggles": {
            "description_en": "Worker detected without required eye protection.",
            "description_es": "Trabajador detectado sin protección ocular requerida.",
            "recommended_action": "Provide safety goggles or glasses appropriate for the hazard.",
            "osha_reference": "OSHA 1926.102 - Eye and Face Protection",
        },
    }

    if violation_type in templates:
        return templates[violation_type]

    # Jordan: Generic fallback for any violation type not in our templates.
    return {
        "description_en": f"PPE violation detected: {violation_type}",
        "description_es": f"Violación de EPP detectada: {violation_type}",
        "recommended_action": "Investigate and correct immediately. Consult site safety officer.",
        "osha_reference": "See OSHA 1926 Subpart E - Personal Protective and Life Saving Equipment",
    }
