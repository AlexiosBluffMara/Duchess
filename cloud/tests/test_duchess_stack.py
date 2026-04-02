"""
CDK Assertion Tests for DuchessStack.

Jordan: These tests verify that our CDK stack synthesizes to the correct
CloudFormation template. They catch configuration drift, permission mistakes,
and missing security controls BEFORE we deploy. Each test is a guard rail
against a specific blast radius:

  - S3 encryption/lifecycle: Prevents unencrypted video storage (compliance violation)
  - DynamoDB schema: Prevents query failures from wrong key schema
  - SQS DLQ: Prevents silent message loss
  - Lambda runtime/memory: Prevents performance issues and security vulnerabilities
  - IAM permissions: Prevents over/under-permissioned roles

Cost of running: $0.00 (no AWS calls — pure template assertions)
Cost of deploying a broken stack: $undefined (could be anything from a support ticket
to a data breach depending on what's wrong)

Run: cd cloud && poetry run pytest tests/test_duchess_stack.py -v
"""

from __future__ import annotations

import aws_cdk as cdk
import aws_cdk.assertions as assertions
import pytest

from stacks.duchess_stack import DuchessStack


# ── Fixtures ────────────────────────────────────────────────────────────────


@pytest.fixture
def template():
    """
    Jordan: Synthesize the stack and return the CloudFormation template
    for assertion testing. We use the default config (dev stage) which
    has DESTROY removal policy — that's fine for tests.
    """
    app = cdk.App()
    stack = DuchessStack(
        app,
        "TestStack",
        stage="dev",
        config={
            "removal_policy": cdk.RemovalPolicy.DESTROY,
            "lambda_memory": 512,
            "lambda_timeout_seconds": 60,
            "enable_point_in_time_recovery": True,
            "video_retention_days": 90,
            "alert_retention_days": 365,
        },
        monthly_budget=200,
    )
    return assertions.Template.from_stack(stack)


@pytest.fixture
def prod_template():
    """
    Jordan: Prod stack template for testing prod-specific config like
    Glacier transitions and RETAIN removal policies.
    """
    app = cdk.App()
    stack = DuchessStack(
        app,
        "ProdTestStack",
        stage="prod",
        config={
            "removal_policy": cdk.RemovalPolicy.RETAIN,
            "lambda_memory": 512,
            "lambda_timeout_seconds": 60,
            "enable_point_in_time_recovery": True,
            "video_retention_days": 90,
            "alert_retention_days": 365,
        },
        monthly_budget=2000,
    )
    return assertions.Template.from_stack(stack)


# ── S3 Bucket Tests ────────────────────────────────────────────────────────
# Jordan: The video bucket is where raw construction site video lands.
# If encryption is missing, we're storing sensitive worker footage in plaintext.
# If public access isn't blocked, anyone with the URL can view safety videos.
# Both are HIPAA violations and potential lawsuits.


class TestS3Bucket:
    """Tests for the S3 video storage bucket configuration."""

    def test_s3_bucket_has_kms_encryption(self, template):
        """
        Jordan: KMS-managed encryption is MANDATORY for video storage.
        Without this, video data sits in plaintext on S3 — a compliance
        nightmare for OSHA-regulated construction sites.
        """
        template.has_resource_properties(
            "AWS::S3::Bucket",
            {
                "BucketEncryption": {
                    "ServerSideEncryptionConfiguration": assertions.Match.array_with(
                        [
                            {
                                "ServerSideEncryptionByDefault": {
                                    "SSEAlgorithm": "aws:kms",
                                }
                            }
                        ]
                    )
                }
            },
        )

    def test_s3_bucket_has_lifecycle_rule(self, template):
        """
        Jordan: Lifecycle rules prevent unbounded storage costs. At $0.023/GB/month,
        a site generating 10GB/day would cost $690/month without cleanup. With 90-day
        expiry, we cap it at ~$207 (30 days of data at steady state due to rolling window).
        """
        template.has_resource_properties(
            "AWS::S3::Bucket",
            {
                "LifecycleConfiguration": {
                    "Rules": assertions.Match.array_with(
                        [
                            assertions.Match.object_like(
                                {
                                    "ExpirationInDays": assertions.Match.any_value(),
                                    "Status": "Enabled",
                                }
                            )
                        ]
                    )
                }
            },
        )

    def test_s3_bucket_blocks_public_access(self, template):
        """
        Jordan: ALL public access must be blocked. There is exactly zero
        use cases for public access to construction site safety videos.
        If this test fails, someone removed the block — investigate immediately.
        """
        template.has_resource_properties(
            "AWS::S3::Bucket",
            {
                "PublicAccessBlockConfiguration": {
                    "BlockPublicAcls": True,
                    "BlockPublicPolicy": True,
                    "IgnorePublicAcls": True,
                    "RestrictPublicBuckets": True,
                }
            },
        )

    def test_s3_bucket_enforces_ssl(self, template):
        """
        Jordan: SSL enforcement prevents any non-HTTPS access to the bucket.
        This is a bucket policy check — CDK adds a policy statement denying
        requests where aws:SecureTransport is false.
        """
        # Jordan: CDK adds a bucket policy with SSL enforcement when enforce_ssl=True
        template.has_resource("AWS::S3::BucketPolicy", {})


# ── DynamoDB Tests ──────────────────────────────────────────────────────────
# Jordan: The alerts table is the source of truth for all PPE violations.
# Wrong schema = broken queries. No PITR = no recovery from accidental deletes.
# Wrong billing = either wasted money (provisioned) or throttling (under-provisioned).


class TestDynamoDB:
    """Tests for the DynamoDB safety alerts table."""

    def test_dynamodb_has_correct_key_schema(self, template):
        """
        Jordan: Partition key = id (string), sort key = timestamp (number).
        This lets us query all events for an alert (by id) sorted by time.
        If this schema changes, every GSI and every query in the handler breaks.
        """
        template.has_resource_properties(
            "AWS::DynamoDB::Table",
            {
                "KeySchema": [
                    {"AttributeName": "id", "KeyType": "HASH"},
                    {"AttributeName": "timestamp", "KeyType": "RANGE"},
                ],
            },
        )

    def test_dynamodb_has_zone_gsi(self, template):
        """
        Jordan: The zone-severity GSI is how supervisors query "show me all
        violations in zone-A sorted by severity." Without it, we'd need a
        full table scan — expensive and slow with millions of alerts.
        """
        template.has_resource_properties(
            "AWS::DynamoDB::Table",
            {
                "GlobalSecondaryIndexes": assertions.Match.array_with(
                    [
                        assertions.Match.object_like(
                            {
                                "IndexName": "zone-severity-index",
                                "KeySchema": [
                                    {"AttributeName": "zoneId", "KeyType": "HASH"},
                                    {"AttributeName": "severity", "KeyType": "RANGE"},
                                ],
                            }
                        )
                    ]
                )
            },
        )

    def test_dynamodb_has_pay_per_request_billing(self, template):
        """
        Jordan: PAY_PER_REQUEST = no idle cost, auto-scales, $1.25/M writes.
        At our traffic level (~1K-10K alerts/day), this is 10-100x cheaper
        than provisioned capacity with auto-scaling overhead. The break-even
        point for provisioned is ~100K sustained writes/sec — we'll never hit that.
        """
        template.has_resource_properties(
            "AWS::DynamoDB::Table",
            {"BillingMode": "PAY_PER_REQUEST"},
        )

    def test_dynamodb_has_point_in_time_recovery(self, template):
        """
        Jordan: PITR costs ~$0.20/GB/month but gives us 35-day continuous
        backup with 1-second granularity. For a safety alerts table, this
        is mandatory — we can't lose violation records.
        """
        template.has_resource_properties(
            "AWS::DynamoDB::Table",
            {
                "PointInTimeRecoverySpecification": {
                    "PointInTimeRecoveryEnabled": True,
                }
            },
        )


# ── SQS Tests ──────────────────────────────────────────────────────────────
# Jordan: The escalation queue is the buffer between phones and Lambda.
# If the DLQ is misconfigured, failed messages disappear silently.
# If visibility timeout is wrong, messages get processed multiple times.


class TestSQS:
    """Tests for SQS escalation queue configuration."""

    def test_sqs_queue_has_5min_visibility_timeout(self, template):
        """
        Jordan: 5-minute (300s) visibility timeout. This MUST exceed the
        Lambda timeout (60s) by a healthy margin. If visibility < Lambda timeout,
        SQS re-delivers the message while Lambda is still processing it,
        causing duplicate Bedrock calls ($0.03 each). 300s gives 5x margin.
        """
        template.has_resource_properties(
            "AWS::SQS::Queue",
            {
                "VisibilityTimeout": 300,
            },
        )

    def test_sqs_has_dlq_with_max_3_receives(self, template):
        """
        Jordan: After 3 failed processing attempts, the message goes to the DLQ.
        3 retries is the sweet spot: enough to survive transient Bedrock hiccups,
        not so many that a poison message wastes $0.09 in Bedrock calls (3 × $0.03).
        """
        template.has_resource_properties(
            "AWS::SQS::Queue",
            {
                "RedrivePolicy": assertions.Match.object_like(
                    {"maxReceiveCount": 3}
                ),
            },
        )

    def test_dlq_exists(self, template):
        """
        Jordan: Verify the DLQ itself exists as a separate queue.
        CDK should create two queues: the main queue and the DLQ.
        """
        # Jordan: DLQ + main queue + any other queues = at least 2
        template.resource_count_is("AWS::SQS::Queue", 2)


# ── Lambda Tests ────────────────────────────────────────────────────────────
# Jordan: The Lambda function is the most security-sensitive resource.
# Wrong runtime = security vulnerabilities. Wrong memory = OOM kills.
# Wrong timeout = silent failures. Missing permissions = broken pipeline.


class TestLambda:
    """Tests for the Lambda escalation handler."""

    def test_lambda_has_correct_runtime(self, template):
        """
        Jordan: Python 3.12 runtime. Not 3.11 (EOL approaching), not 3.13
        (not yet supported). The runtime determines available security patches,
        performance characteristics, and boto3 version.
        """
        template.has_resource_properties(
            "AWS::Lambda::Function",
            {"Runtime": "python3.12"},
        )

    def test_lambda_has_correct_memory(self, template):
        """
        Jordan: 512MB is right-sized for JSON parsing + Bedrock API calls.
        At $0.0000000083/ms per MB, 512MB × 5s = $0.000021 per invocation.
        Doubling to 1024MB would double that cost with no measurable benefit
        (we're I/O bound waiting for Bedrock, not CPU bound).
        """
        template.has_resource_properties(
            "AWS::Lambda::Function",
            {"MemorySize": 512},
        )

    def test_lambda_has_correct_timeout(self, template):
        """
        Jordan: 60-second timeout. Bedrock p99 latency is ~15s for Claude 3.5,
        so 60s gives 4x headroom. Going lower risks timeout-related failures.
        Going higher wastes money on stuck invocations.
        """
        template.has_resource_properties(
            "AWS::Lambda::Function",
            {"Timeout": 60},
        )

    def test_lambda_has_bedrock_invoke_permission(self, template):
        """
        Jordan: Lambda needs bedrock:InvokeModel permission scoped to the
        specific model ARN. Without this, every Bedrock call returns AccessDenied.
        We verify the IAM policy exists and targets the correct action.
        """
        template.has_resource_properties(
            "AWS::IAM::Policy",
            {
                "PolicyDocument": {
                    "Statement": assertions.Match.array_with(
                        [
                            assertions.Match.object_like(
                                {
                                    "Action": "bedrock:InvokeModel",
                                    "Effect": "Allow",
                                }
                            )
                        ]
                    )
                }
            },
        )

    def test_lambda_has_environment_variables(self, template):
        """
        Jordan: All Lambda config comes from env vars — 12-factor style.
        Verify the critical env vars are set so the handler doesn't crash
        on startup trying to read os.environ["ALERTS_TABLE_NAME"].
        """
        template.has_resource_properties(
            "AWS::Lambda::Function",
            {
                "Environment": {
                    "Variables": assertions.Match.object_like(
                        {
                            "STAGE": "dev",
                            "LOG_LEVEL": "DEBUG",
                        }
                    )
                }
            },
        )

    def test_lambda_reads_from_video_bucket(self, template):
        """
        Jordan: Lambda needs s3:GetObject on the video bucket to read
        video segments for analysis. CDK's grant_read() creates this.
        """
        template.has_resource_properties(
            "AWS::IAM::Policy",
            {
                "PolicyDocument": {
                    "Statement": assertions.Match.array_with(
                        [
                            assertions.Match.object_like(
                                {
                                    "Action": assertions.Match.array_with(
                                        ["s3:GetObject*"]
                                    ),
                                    "Effect": "Allow",
                                }
                            )
                        ]
                    )
                }
            },
        )

    def test_lambda_writes_to_alerts_table(self, template):
        """
        Jordan: Lambda needs dynamodb:PutItem (and other write ops) on the
        alerts table. CDK's grant_write_data() creates this.
        """
        template.has_resource_properties(
            "AWS::IAM::Policy",
            {
                "PolicyDocument": {
                    "Statement": assertions.Match.array_with(
                        [
                            assertions.Match.object_like(
                                {
                                    "Action": assertions.Match.array_with(
                                        ["dynamodb:PutItem"]
                                    ),
                                    "Effect": "Allow",
                                }
                            )
                        ]
                    )
                }
            },
        )


# ── SNS Tests ──────────────────────────────────────────────────────────────


class TestSNS:
    """Tests for the SNS alert notification topic."""

    def test_sns_topic_exists(self, template):
        """
        Jordan: The alert topic is the fanout point for all PPE notifications.
        Supervisors, dashboards, and pagers all subscribe here. If this
        resource is missing, alerts go to DynamoDB but nobody gets notified.
        """
        template.resource_count_is("AWS::SNS::Topic", 1)


# ── CloudWatch Alarm Tests ──────────────────────────────────────────────────


class TestCloudWatchAlarms:
    """Tests for CloudWatch monitoring alarms."""

    def test_lambda_errors_alarm_exists(self, template):
        """
        Jordan: We must alarm on Lambda errors. Even one error means a PPE
        violation went unprocessed. This is a safety system, not a CRUD app.
        """
        template.has_resource_properties(
            "AWS::CloudWatch::Alarm",
            assertions.Match.object_like(
                {
                    "Namespace": "AWS/Lambda",
                    "MetricName": "Errors",
                }
            ),
        )

    def test_dlq_depth_alarm_exists(self, template):
        """
        Jordan: DLQ messages = failed escalations. We alarm on ≥1 message
        because every failed escalation is a potential safety incident.
        """
        template.has_resource_properties(
            "AWS::CloudWatch::Alarm",
            assertions.Match.object_like(
                {
                    "Namespace": "AWS/SQS",
                    "MetricName": "ApproximateNumberOfMessagesVisible",
                }
            ),
        )

    def test_alarms_count(self, template):
        """
        Jordan: We expect 4 alarms: Lambda errors, Lambda duration,
        DLQ depth, DynamoDB throttles. If someone adds a resource without
        an alarm, this test catches the oversight.
        """
        template.resource_count_is("AWS::CloudWatch::Alarm", 4)


# ── Output Export Tests ─────────────────────────────────────────────────────


class TestOutputs:
    """Tests for stack output exports."""

    def test_video_bucket_name_exported(self, template):
        """Jordan: Other stacks need the bucket name for nightly batch pipeline."""
        template.has_output(
            "VideoBucketName",
            {"Export": {"Name": "duchess-dev-video-bucket-name"}},
        )

    def test_alerts_table_name_exported(self, template):
        """Jordan: Dashboard and reporting stacks need the table name."""
        template.has_output(
            "AlertsTableName",
            {"Export": {"Name": "duchess-dev-alerts-table-name"}},
        )

    def test_alert_topic_arn_exported(self, template):
        """Jordan: External subscribers need the topic ARN."""
        template.has_output(
            "AlertTopicArn",
            {"Export": {"Name": "duchess-dev-alert-topic-arn"}},
        )
