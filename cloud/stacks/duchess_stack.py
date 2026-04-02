"""
Duchess CDK Stack — PPE escalation pipeline and batch infrastructure.

Resources:
  - S3 bucket: video storage (KMS encrypted, 90-day lifecycle)
  - DynamoDB table: safety alerts
  - SQS queue: PPE escalation queue
  - Lambda function: PPE escalation handler (Bedrock Claude)
  - SageMaker endpoint: placeholder (commented out — cost-conscious)
"""

from __future__ import annotations

from aws_cdk import (
    Duration,
    RemovalPolicy,
    Stack,
    aws_dynamodb as dynamodb,
    aws_iam as iam,
    aws_lambda as lambda_,
    aws_s3 as s3,
    aws_sqs as sqs,
    aws_lambda_event_sources as event_sources,
)
from constructs import Construct


class DuchessStack(Stack):
    def __init__(self, scope: Construct, construct_id: str, **kwargs) -> None:
        super().__init__(scope, construct_id, **kwargs)

        # ── S3: Video Storage ───────────────────────────────────────────────
        self.video_bucket = s3.Bucket(
            self,
            "DuchessVideoBucket",
            bucket_name=f"duchess-video-{self.account}-{self.region}",
            encryption=s3.BucketEncryption.KMS_MANAGED,
            block_public_access=s3.BlockPublicAccess.BLOCK_ALL,
            versioned=False,
            removal_policy=RemovalPolicy.RETAIN,
            lifecycle_rules=[
                s3.LifecycleRule(
                    id="ExpireAfter90Days",
                    expiration=Duration.days(90),
                    enabled=True,
                ),
            ],
        )

        # ── DynamoDB: Safety Alerts ─────────────────────────────────────────
        self.alerts_table = dynamodb.Table(
            self,
            "DuchessAlertsTable",
            table_name="duchess-alerts",
            partition_key=dynamodb.Attribute(
                name="id",
                type=dynamodb.AttributeType.STRING,
            ),
            sort_key=dynamodb.Attribute(
                name="timestamp",
                type=dynamodb.AttributeType.NUMBER,
            ),
            billing_mode=dynamodb.BillingMode.PAY_PER_REQUEST,
            removal_policy=RemovalPolicy.RETAIN,
            point_in_time_recovery=True,
        )

        # GSI for querying by zone
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
        self.escalation_dlq = sqs.Queue(
            self,
            "DuchessEscalationDLQ",
            queue_name="duchess-escalation-dlq",
            retention_period=Duration.days(14),
        )

        self.escalation_queue = sqs.Queue(
            self,
            "DuchessEscalationQueue",
            queue_name="duchess-escalation",
            visibility_timeout=Duration.minutes(5),
            retention_period=Duration.days(4),
            dead_letter_queue=sqs.DeadLetterQueue(
                max_receive_count=3,
                queue=self.escalation_dlq,
            ),
        )

        # ── Lambda: PPE Escalation Handler ──────────────────────────────────
        self.escalation_handler = lambda_.Function(
            self,
            "DuchessPpeEscalationHandler",
            function_name="duchess-ppe-escalation",
            runtime=lambda_.Runtime.PYTHON_3_12,
            handler="handler.lambda_handler",
            code=lambda_.Code.from_asset("lambda/ppe_escalation"),
            memory_size=512,
            timeout=Duration.seconds(60),
            environment={
                "ALERTS_TABLE_NAME": self.alerts_table.table_name,
                "VIDEO_BUCKET_NAME": self.video_bucket.bucket_name,
                "BEDROCK_MODEL_ID": "anthropic.claude-3-5-sonnet-20241022-v2:0",
            },
        )

        # Lambda permissions
        self.alerts_table.grant_write_data(self.escalation_handler)
        self.video_bucket.grant_read(self.escalation_handler)

        # Bedrock invoke permission
        self.escalation_handler.add_to_role_policy(
            iam.PolicyStatement(
                actions=["bedrock:InvokeModel"],
                resources=[
                    f"arn:aws:bedrock:{self.region}::foundation-model/anthropic.claude-3-5-sonnet-20241022-v2:0"
                ],
            )
        )

        # Wire SQS → Lambda
        self.escalation_handler.add_event_source(
            event_sources.SqsEventSource(
                self.escalation_queue,
                batch_size=1,
            )
        )

        # ── SageMaker Endpoint (Placeholder — commented out for cost) ──────
        #
        # from aws_cdk import aws_sagemaker as sagemaker
        #
        # self.ppe_endpoint = sagemaker.CfnEndpoint(
        #     self, "DuchessPpeEndpoint",
        #     endpoint_config_name="duchess-ppe-yolov8-config",
        # )
        #
        # Uncomment and configure when ready for cloud-tier PPE inference.
        # Use ml-inf2 instance for cost-effective inference.
