"""
LEGACY — AWS CDK Stack. Scheduled for replacement by cloud-gcp/ (Google Cloud).

The hackathon pivot (April 2026) moves Duchess from AWS to Google Cloud (Vertex AI,
Cloud Run, Firestore, Cloud Storage, Pub/Sub). This stack remains as reference
architecture but will NOT be deployed for the Gemma 4 Good Hackathon.

Migration plan: see .memory/hackathon-pivot-plan.md → Phase 4 (cloud-gcp/).
Equivalent GCP resources:
  - S3 → Cloud Storage (same encryption, lifecycle policies)
  - DynamoDB → Firestore (real-time sync to all devices, better for mobile)
  - SQS → Pub/Sub (exactly-once delivery, better scaling model)
  - Lambda → Cloud Run (serverless, but with container flexibility)
  - Bedrock Claude → Vertex AI Gemma 4 31B (open-weight, Apache 2.0)
  - SNS → Firebase Cloud Messaging (FCM) for push notifications
  - CloudWatch → Cloud Monitoring + Error Reporting

TODO-PRINCIPAL: Before deleting this stack:
  1. Extract the IAM least-privilege patterns — they're well-done and should be
     replicated in GCP IAM (service accounts with minimal roles).
  2. Extract the CloudWatch alarm patterns — same alarm philosophy applies to
     Cloud Monitoring (zero-tolerance on safety pipeline failures).
  3. The DLQ + max_receive_count=3 pattern → Pub/Sub dead-letter topic equivalent.
  4. Tag strategy → GCP labels (same cost allocation purpose).
  5. Keep this file as-is until cloud-gcp/ is fully operational and tested.

TODO-ML-PROF: The Bedrock Claude 3.5 model ID should be replaced by Gemma 4 31B on
  Vertex AI for the hackathon. Key differences:
  - Bedrock pricing ($3/$15 per 1K tokens) vs Vertex AI Gemma 4 (~$0.02/query on T4)
  - Gemma 4 supports native function calling — no need for JSON parsing workarounds
  - Gemma 4 31B Dense runs on g2-standard-48 (L4 x4) or n1-standard-8 + T4 for demo
  - Apache 2.0 license = no usage restrictions, can fine-tune and publish weights

Duchess CDK Stack — PPE escalation pipeline and batch infrastructure.

# Jordan: This is THE stack. Every resource here costs money, every resource here
# handles sensitive construction worker data, and every resource here needs monitoring.
# If a resource doesn't have a CloudWatch alarm, a tag, or an encryption config,
# it doesn't belong in this stack. Period.
#
# Cost breakdown (estimated monthly at low traffic):
#   S3 (10GB video):    ~$0.23/month (Standard tier, $0.023/GB)
#   DynamoDB (on-demand): ~$1.25 per million writes, $0.25 per million reads
#   SQS:                 ~$0.40 per million requests (first 1M free)
#   Lambda (10K invocations × 5s avg): ~$0.50/month
#   Bedrock Claude 3.5:  ~$3/1K input tokens, $15/1K output tokens
#   SNS:                 First 1M free, then $0.50/million
#   CloudWatch alarms:   $0.10/alarm/month (standard)
#   TOTAL (low traffic): ~$15-30/month
#
# At scale (100K daily PPE checks): budget $500-800/month.

Resources:
  - S3 bucket: video storage (KMS encrypted, lifecycle policy)
  - DynamoDB table: safety alerts (PITR, zone GSI)
  - SQS queue: PPE escalation queue (DLQ, visibility timeout)
  - SQS DLQ: dead-letter queue for failed messages
  - Lambda function: PPE escalation handler (Bedrock Claude)
  - SNS topic: alert notifications (human review, supervisors)
  - CloudWatch alarms: Lambda errors, DLQ depth, DynamoDB throttles
"""

from __future__ import annotations

from aws_cdk import (
    CfnOutput,
    Duration,
    RemovalPolicy,
    Stack,
    Tags,
    aws_cloudwatch as cloudwatch,
    aws_cloudwatch_actions as cw_actions,
    aws_dynamodb as dynamodb,
    aws_iam as iam,
    aws_lambda as lambda_,
    aws_lambda_event_sources as event_sources,
    aws_s3 as s3,
    aws_sns as sns,
    aws_sns_subscriptions as sns_subs,
    aws_sqs as sqs,
)
from constructs import Construct


class DuchessStack(Stack):
    """
    Jordan: The Duchess infrastructure stack. Every resource is tagged, monitored,
    and cost-conscious. We build for three environments (dev/staging/prod) with
    different retention policies and resource sizing.
    """

    def __init__(
        self,
        scope: Construct,
        construct_id: str,
        *,
        stage: str = "dev",
        config: dict | None = None,
        monthly_budget: int = 200,
        **kwargs,
    ) -> None:
        super().__init__(scope, construct_id, **kwargs)

        # Jordan: Default config for backward compatibility / direct instantiation in tests
        if config is None:
            config = {
                "removal_policy": RemovalPolicy.DESTROY,
                "lambda_memory": 512,
                "lambda_timeout_seconds": 60,
                "enable_point_in_time_recovery": True,
                "video_retention_days": 90,
                "alert_retention_days": 365,
            }

        self.stage = stage
        self.config = config

        # ── Tags: Applied to EVERY resource in this stack ───────────────────
        # Jordan: Tags are how we track cost allocation and ownership. If you skip
        # tags, finance will hunt you down when the bill arrives. Non-negotiable.
        Tags.of(self).add("Project", "Duchess")
        Tags.of(self).add("Stage", stage)
        Tags.of(self).add("Team", "safety-platform")
        Tags.of(self).add("CostCenter", "construction-ai")
        Tags.of(self).add("DataClassification", "sensitive")
        Tags.of(self).add("ManagedBy", "cdk")

        # ── SNS: Alert Notifications ────────────────────────────────────────
        # Jordan: Central notification fanout. Human reviewers, supervisors, and
        # ops teams all subscribe here. ~$0.50/million publishes after free tier.
        # In prod, this is the lifeline — if this topic is dead, alerts don't flow.
        self.alert_topic = sns.Topic(
            self,
            "DuchessAlertTopic",
            topic_name=f"duchess-alerts-{stage}",
            display_name=f"Duchess Safety Alerts [{stage.upper()}]",
        )

        # Jordan: Ops email subscription for CRITICAL alerts. In prod, this goes
        # to the on-call rotation. We add subscriptions here so they're in IaC,
        # not manually configured in the console where they get lost.
        # NOTE: Email subscriptions require manual confirmation — CDK can't auto-confirm.
        # For now we export the topic ARN so apps can subscribe programmatically.

        # ── S3: Video Storage ───────────────────────────────────────────────
        # Jordan: This bucket costs us ~$0.023/GB/month on Standard tier. With
        # 90-day lifecycle in prod (7 days in dev), storage costs stay bounded.
        # KMS encryption is MANDATORY — OSHA violations involve worker safety data.
        # Block ALL public access — there is zero reason for this bucket to be public.
        self.video_bucket = s3.Bucket(
            self,
            "DuchessVideoBucket",
            bucket_name=f"duchess-video-{stage}-{self.account}-{self.region}",
            encryption=s3.BucketEncryption.KMS_MANAGED,
            block_public_access=s3.BlockPublicAccess.BLOCK_ALL,
            versioned=False,
            enforce_ssl=True,  # Jordan: Reject any non-HTTPS request. No exceptions.
            removal_policy=config["removal_policy"],
            auto_delete_objects=config["removal_policy"] == RemovalPolicy.DESTROY,
            lifecycle_rules=[
                s3.LifecycleRule(
                    id="ExpireVideoAfterRetention",
                    expiration=Duration.days(config["video_retention_days"]),
                    enabled=True,
                ),
                # Jordan: Move to Glacier after 30 days in prod to save ~68% on
                # storage costs ($0.004/GB vs $0.023/GB). Only in prod because
                # Glacier retrieval is slow and costs extra — not worth it for dev.
                *(
                    [
                        s3.LifecycleRule(
                            id="TransitionToGlacier30Days",
                            transitions=[
                                s3.Transition(
                                    storage_class=s3.StorageClass.GLACIER,
                                    transition_after=Duration.days(30),
                                )
                            ],
                            enabled=True,
                        )
                    ]
                    if stage == "prod"
                    else []
                ),
            ],
        )

        # ── DynamoDB: Safety Alerts ─────────────────────────────────────────
        # Jordan: PAY_PER_REQUEST billing is the right call here. We don't have
        # steady-state traffic patterns yet — spiky PPE violations during shift
        # hours, near-zero overnight. Provisioned capacity would either over-provision
        # (wasting money) or under-provision (throttling alerts). On-demand scales
        # to zero cost when idle. At ~$1.25/million writes, even 100K daily alerts
        # costs <$4/month. That's a rounding error compared to Bedrock inference.
        self.alerts_table = dynamodb.Table(
            self,
            "DuchessAlertsTable",
            table_name=f"duchess-alerts-{stage}",
            partition_key=dynamodb.Attribute(
                name="id",
                type=dynamodb.AttributeType.STRING,
            ),
            sort_key=dynamodb.Attribute(
                name="timestamp",
                type=dynamodb.AttributeType.NUMBER,
            ),
            billing_mode=dynamodb.BillingMode.PAY_PER_REQUEST,
            removal_policy=config["removal_policy"],
            point_in_time_recovery=config["enable_point_in_time_recovery"],
        )

        # Jordan: GSI for querying alerts by zone. Construction sites are zoned
        # (e.g., "zone-A-framing", "zone-B-excavation") so supervisors can pull
        # alerts for their area. Sort by severity so the worst violations surface first.
        self.alerts_table.add_global_secondary_index(
            index_name="zone-severity-index",
            partition_key=dynamodb.Attribute(
                name="zoneId",
                type=dynamodb.AttributeType.STRING,
            ),
            sort_key=dynamodb.Attribute(
                name="severity",
                type=dynamodb.AttributeType.NUMBER,
            ),
        )

        # ── SQS: Escalation Queue ──────────────────────────────────────────
        # Jordan: The DLQ catches messages that fail 3 times. At that point,
        # something is structurally wrong (bad message format, Bedrock outage,
        # permission issue) and we need human eyes. 14-day retention gives ops
        # time to investigate without losing evidence.
        self.escalation_dlq = sqs.Queue(
            self,
            "DuchessEscalationDLQ",
            queue_name=f"duchess-escalation-dlq-{stage}",
            retention_period=Duration.days(14),
            enforce_ssl=True,  # Jordan: Encrypt in transit. Always.
        )

        # Jordan: 5-minute visibility timeout matches Lambda's 60s timeout with
        # margin. If Lambda takes 60s and fails, the message becomes visible again
        # after 5 min for retry. Aggressive enough to retry quickly, not so
        # aggressive that we hammer a failing service.
        self.escalation_queue = sqs.Queue(
            self,
            "DuchessEscalationQueue",
            queue_name=f"duchess-escalation-{stage}",
            visibility_timeout=Duration.minutes(5),
            retention_period=Duration.days(4),
            enforce_ssl=True,
            dead_letter_queue=sqs.DeadLetterQueue(
                max_receive_count=3,
                queue=self.escalation_dlq,
            ),
        )

        # ── Lambda: PPE Escalation Handler ──────────────────────────────────
        # Jordan: 512MB memory in prod is right-sized for JSON parsing + Bedrock
        # API calls. We're not doing image processing in Lambda — that would need
        # 1-2GB. The 60s timeout gives Bedrock enough time to respond even under
        # load (p99 is ~15s for Claude 3.5 Sonnet). Reserved concurrency prevents
        # a traffic spike from blowing through our Bedrock quota.
        self.escalation_handler = lambda_.Function(
            self,
            "DuchessPpeEscalationHandler",
            function_name=f"duchess-ppe-escalation-{stage}",
            runtime=lambda_.Runtime.PYTHON_3_12,
            handler="handler.lambda_handler",
            code=lambda_.Code.from_asset("lambda/ppe_escalation"),
            memory_size=config["lambda_memory"],
            timeout=Duration.seconds(config["lambda_timeout_seconds"]),
            reserved_concurrent_executions=10 if stage == "prod" else 2,
            environment={
                # Jordan: Every config value is an env var — no hardcoded strings
                # in Lambda code. This is how you do 12-factor on serverless.
                "ALERTS_TABLE_NAME": self.alerts_table.table_name,
                "VIDEO_BUCKET_NAME": self.video_bucket.bucket_name,
                "BEDROCK_MODEL_ID": "anthropic.claude-3-5-sonnet-20241022-v2:0",
                "ALERT_TOPIC_ARN": self.alert_topic.topic_arn,
                "STAGE": stage,
                "DLQ_URL": self.escalation_dlq.queue_url,
                "POWERTOOLS_SERVICE_NAME": "duchess-ppe-escalation",
                "LOG_LEVEL": "DEBUG" if stage == "dev" else "INFO",
            },
        )

        # ── Lambda Permissions ──────────────────────────────────────────────
        # Jordan: Least-privilege IAM. Each grant is scoped to the specific
        # resource, not "dynamodb:*" on "*". That's how you get breached.
        self.alerts_table.grant_write_data(self.escalation_handler)
        self.alerts_table.grant_read_data(self.escalation_handler)
        self.video_bucket.grant_read(self.escalation_handler)
        self.alert_topic.grant_publish(self.escalation_handler)

        # Jordan: Bedrock invoke scoped to the exact model ARN. Not bedrock:*
        # which would let the Lambda call any model (including expensive ones).
        self.escalation_handler.add_to_role_policy(
            iam.PolicyStatement(
                actions=["bedrock:InvokeModel"],
                resources=[
                    f"arn:aws:bedrock:{self.region}::foundation-model/"
                    f"anthropic.claude-3-5-sonnet-20241022-v2:0"
                ],
            )
        )

        # Jordan: Wire SQS → Lambda. batch_size=1 because each PPE escalation
        # is independent and we want per-message error isolation. If we batched 10
        # and one failed, the whole batch retries — wasting Bedrock inference $.
        self.escalation_handler.add_event_source(
            event_sources.SqsEventSource(
                self.escalation_queue,
                batch_size=1,
            )
        )

        # ── CloudWatch Alarms ───────────────────────────────────────────────
        # Jordan: Alarms are not optional. If the Lambda is failing silently,
        # PPE violations go unreviewed and someone gets hurt. These alarms are
        # the safety net for our safety net.

        # Alarm: Lambda errors (any invocation error in 5 min)
        # Jordan: Even ONE error is worth investigating for a safety system.
        # In a normal service I'd set threshold at 5-10. For PPE: zero tolerance.
        lambda_errors_alarm = cloudwatch.Alarm(
            self,
            "LambdaErrorsAlarm",
            alarm_name=f"duchess-lambda-errors-{stage}",
            alarm_description=(
                "PPE escalation Lambda function is throwing errors. "
                "Unprocessed PPE violations may be accumulating in the queue."
            ),
            metric=self.escalation_handler.metric_errors(
                period=Duration.minutes(5),
                statistic="Sum",
            ),
            threshold=1,
            evaluation_periods=1,
            comparison_operator=cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treat_missing_data=cloudwatch.TreatMissingData.NOT_BREACHING,
        )
        lambda_errors_alarm.add_alarm_action(cw_actions.SnsAction(self.alert_topic))

        # Alarm: Lambda duration approaching timeout
        # Jordan: If p99 latency hits 80% of timeout, we're one slow Bedrock
        # response away from timeouts. This gives us lead time to investigate.
        lambda_duration_alarm = cloudwatch.Alarm(
            self,
            "LambdaDurationAlarm",
            alarm_name=f"duchess-lambda-duration-{stage}",
            alarm_description=(
                "PPE escalation Lambda p99 duration exceeding 80% of timeout. "
                "Bedrock may be slow or Lambda needs more memory."
            ),
            metric=self.escalation_handler.metric_duration(
                period=Duration.minutes(5),
                statistic="p99",
            ),
            threshold=config["lambda_timeout_seconds"] * 1000 * 0.8,  # 80% of timeout in ms
            evaluation_periods=2,
            comparison_operator=cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treat_missing_data=cloudwatch.TreatMissingData.NOT_BREACHING,
        )
        lambda_duration_alarm.add_alarm_action(cw_actions.SnsAction(self.alert_topic))

        # Alarm: DLQ messages (any message in DLQ = something failed 3 times)
        # Jordan: Messages in the DLQ are PPE violations that DIDN'T get processed.
        # That's a safety incident in itself. Threshold is 1 because even one
        # unprocessed violation is unacceptable.
        dlq_alarm = cloudwatch.Alarm(
            self,
            "DLQDepthAlarm",
            alarm_name=f"duchess-dlq-depth-{stage}",
            alarm_description=(
                "Messages in the dead-letter queue. PPE violations failed processing "
                "3 times and need manual investigation."
            ),
            metric=self.escalation_dlq.metric_approximate_number_of_messages_visible(
                period=Duration.minutes(1),
                statistic="Sum",
            ),
            threshold=1,
            evaluation_periods=1,
            comparison_operator=cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treat_missing_data=cloudwatch.TreatMissingData.NOT_BREACHING,
        )
        dlq_alarm.add_alarm_action(cw_actions.SnsAction(self.alert_topic))

        # Alarm: DynamoDB throttles (shouldn't happen with PAY_PER_REQUEST but
        # AWS has service limits and bugs — trust but verify)
        # Jordan: If we see throttles on PAY_PER_REQUEST, something is very wrong
        # at the AWS level or we're hitting account limits. Worth paging for.
        dynamo_throttle_alarm = cloudwatch.Alarm(
            self,
            "DynamoThrottleAlarm",
            alarm_name=f"duchess-dynamo-throttle-{stage}",
            alarm_description=(
                "DynamoDB write throttles detected on alerts table. "
                "May need to contact AWS support or check account limits."
            ),
            metric=self.alerts_table.metric_throttled_requests_for_operation(
                "PutItem",
                period=Duration.minutes(5),
                statistic="Sum",
            ),
            threshold=1,
            evaluation_periods=1,
            comparison_operator=cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            treat_missing_data=cloudwatch.TreatMissingData.NOT_BREACHING,
        )
        dynamo_throttle_alarm.add_alarm_action(cw_actions.SnsAction(self.alert_topic))

        # ── CfnOutput: Exports ──────────────────────────────────────────────
        # Jordan: Export every resource ARN/URL so other stacks and scripts can
        # reference them. The nightly batch stack (coming soon) will import the
        # bucket name and table name from here.
        CfnOutput(
            self,
            "VideoBucketName",
            value=self.video_bucket.bucket_name,
            export_name=f"duchess-{stage}-video-bucket-name",
            description="S3 bucket for video segment storage",
        )
        CfnOutput(
            self,
            "VideoBucketArn",
            value=self.video_bucket.bucket_arn,
            export_name=f"duchess-{stage}-video-bucket-arn",
            description="S3 bucket ARN for cross-stack references",
        )
        CfnOutput(
            self,
            "AlertsTableName",
            value=self.alerts_table.table_name,
            export_name=f"duchess-{stage}-alerts-table-name",
            description="DynamoDB table for safety alerts",
        )
        CfnOutput(
            self,
            "AlertsTableArn",
            value=self.alerts_table.table_arn,
            export_name=f"duchess-{stage}-alerts-table-arn",
            description="DynamoDB table ARN for cross-stack references",
        )
        CfnOutput(
            self,
            "EscalationQueueUrl",
            value=self.escalation_queue.queue_url,
            export_name=f"duchess-{stage}-escalation-queue-url",
            description="SQS queue URL for PPE escalation messages",
        )
        CfnOutput(
            self,
            "EscalationQueueArn",
            value=self.escalation_queue.queue_arn,
            export_name=f"duchess-{stage}-escalation-queue-arn",
            description="SQS queue ARN for cross-stack references",
        )
        CfnOutput(
            self,
            "AlertTopicArn",
            value=self.alert_topic.topic_arn,
            export_name=f"duchess-{stage}-alert-topic-arn",
            description="SNS topic ARN for alert notifications",
        )
        CfnOutput(
            self,
            "EscalationHandlerArn",
            value=self.escalation_handler.function_arn,
            export_name=f"duchess-{stage}-escalation-handler-arn",
            description="Lambda function ARN for PPE escalation handler",
        )
        CfnOutput(
            self,
            "DLQUrl",
            value=self.escalation_dlq.queue_url,
            export_name=f"duchess-{stage}-dlq-url",
            description="Dead-letter queue URL for failed escalation messages",
        )

        # ── SageMaker Endpoint (Placeholder) ────────────────────────────────
        # Jordan: NOT deploying a SageMaker endpoint until we have steady traffic.
        # A single ml.inf2.xlarge is ~$0.76/hr = $547/month IDLE. We'll use
        # Bedrock on-demand until traffic justifies a dedicated endpoint.
        # The break-even point is roughly 50K inferences/month.
        #
        # from aws_cdk import aws_sagemaker as sagemaker
        #
        # self.ppe_endpoint = sagemaker.CfnEndpoint(
        #     self, "DuchessPpeEndpoint",
        #     endpoint_config_name=f"duchess-ppe-yolov8-config-{stage}",
        # )
