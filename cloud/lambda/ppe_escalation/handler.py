"""
PPE Escalation Lambda Handler.

Triggered by SQS messages from the escalation queue.
Calls Bedrock Claude to analyze the escalation, writes result to DynamoDB.

PRIVACY: No worker PII is stored in DynamoDB. The anonymizedWorkerId field
is a hash — never a name, badge number, or identifiable information.
"""

from __future__ import annotations

import json
import os
import time
import uuid

import boto3

# ── Clients ─────────────────────────────────────────────────────────────────

dynamodb = boto3.resource("dynamodb")
bedrock = boto3.client("bedrock-runtime")
s3 = boto3.client("s3")

ALERTS_TABLE_NAME = os.environ["ALERTS_TABLE_NAME"]
VIDEO_BUCKET_NAME = os.environ["VIDEO_BUCKET_NAME"]
BEDROCK_MODEL_ID = os.environ.get(
    "BEDROCK_MODEL_ID", "anthropic.claude-3-5-sonnet-20241022-v2:0"
)


def lambda_handler(event: dict, context) -> dict:
    """Process PPE escalation events from SQS."""
    alerts_table = dynamodb.Table(ALERTS_TABLE_NAME)
    results = []

    for record in event.get("Records", []):
        body = json.loads(record["body"])
        result = process_escalation(body, alerts_table)
        results.append(result)

    return {
        "statusCode": 200,
        "body": json.dumps({"processed": len(results), "results": results}),
    }


def process_escalation(escalation: dict, alerts_table) -> dict:
    """
    Process a single PPE escalation:
      1. Read escalation details
      2. Call Bedrock Claude for analysis
      3. Write alert to DynamoDB
    """
    alert_id = str(uuid.uuid4())
    timestamp = int(time.time() * 1000)

    violation_type = escalation.get("violation_type", "unknown")
    zone_id = escalation.get("zone_id", "unknown")
    severity = escalation.get("severity", 1)
    description = escalation.get("description", "")

    # ── Bedrock Analysis ────────────────────────────────────────────────────
    analysis = call_bedrock_analysis(violation_type, description)

    # ── Write to DynamoDB ───────────────────────────────────────────────────
    # PRIVACY: No worker PII — only anonymized data
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
    }

    alerts_table.put_item(Item=alert_item)

    return {"alert_id": alert_id, "status": "created"}


def call_bedrock_analysis(violation_type: str, description: str) -> dict:
    """
    Call Bedrock Claude to analyze a PPE violation and generate
    bilingual recommendations.
    """
    prompt = f"""You are a construction safety expert. Analyze this PPE violation and provide:
1. A brief description in English
2. A brief description in Spanish
3. The recommended corrective action
4. The relevant OSHA standard reference

Violation type: {violation_type}
Scene description: {description}

Respond in JSON format:
{{
    "description_en": "...",
    "description_es": "...",
    "recommended_action": "...",
    "osha_reference": "..."
}}"""

    try:
        response = bedrock.invoke_model(
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
        return json.loads(content)

    except Exception as e:
        # Fallback if Bedrock call fails
        return {
            "description_en": f"PPE violation detected: {violation_type}",
            "description_es": f"Violación de EPP detectada: {violation_type}",
            "recommended_action": "Investigate and correct immediately",
            "osha_reference": "See OSHA 1926 Subpart E",
            "error": str(e),
        }
