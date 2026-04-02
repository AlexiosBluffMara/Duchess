#!/usr/bin/env python3
"""
Duchess Cloud — CDK App Entry Point.

Deploys the PPE escalation pipeline and nightly batch infrastructure.

# Jordan: This is the CDK app entry point. We support three stages — dev, staging,
# prod — because I've seen too many teams test against prod and eat $800 in Bedrock
# calls in one night. Stage isolation is non-negotiable. Each stage gets its own
# stack with isolated resources. No cross-stage contamination.
#
# Usage:
#   cdk deploy --context stage=dev --context account=123456789012
#   cdk deploy --context stage=prod --context account=123456789012 --context region=us-east-1
#
# Cost note: dev/staging use on-demand everything. prod will eventually get
# reserved capacity once we have steady-state traffic numbers.
"""

from __future__ import annotations

import aws_cdk as cdk

from stacks.duchess_stack import DuchessStack

# Jordan: Valid stages. If someone passes "test" or "qa" we fail fast rather than
# deploying to an ambiguous environment. Blast radius control starts at the CLI.
VALID_STAGES = {"dev", "staging", "prod"}

# Jordan: Default config per stage. Memory/timeout tuned per environment.
# Dev gets 256MB because we're not running real Bedrock calls there (mocked).
# Prod gets 512MB for headroom on Bedrock response parsing.
STAGE_CONFIG: dict[str, dict] = {
    "dev": {
        "removal_policy": cdk.RemovalPolicy.DESTROY,
        "lambda_memory": 256,
        "lambda_timeout_seconds": 30,
        "enable_point_in_time_recovery": False,
        "video_retention_days": 7,
        "alert_retention_days": 30,
    },
    "staging": {
        "removal_policy": cdk.RemovalPolicy.DESTROY,
        "lambda_memory": 512,
        "lambda_timeout_seconds": 60,
        "enable_point_in_time_recovery": True,
        "video_retention_days": 30,
        "alert_retention_days": 90,
    },
    "prod": {
        "removal_policy": cdk.RemovalPolicy.RETAIN,
        "lambda_memory": 512,
        "lambda_timeout_seconds": 60,
        "enable_point_in_time_recovery": True,
        "video_retention_days": 90,
        "alert_retention_days": 365,
    },
}

app = cdk.App()

# Jordan: Pull stage from CDK context. Default to dev so nobody accidentally
# deploys to prod by omitting a flag. That's a career-ending mistake I've seen.
stage = app.node.try_get_context("stage") or "dev"
if stage not in VALID_STAGES:
    raise ValueError(
        f"Invalid stage '{stage}'. Must be one of: {', '.join(sorted(VALID_STAGES))}. "
        f"Pass --context stage=dev|staging|prod"
    )

account = app.node.try_get_context("account")
region = app.node.try_get_context("region") or "us-east-1"

# Jordan: Monthly budget alarm threshold in USD. Override with --context budget=500.
# Default $200 for dev, $500 staging, $2000 prod. These are soft guardrails.
budget_defaults = {"dev": 200, "staging": 500, "prod": 2000}
monthly_budget = int(
    app.node.try_get_context("budget") or budget_defaults.get(stage, 200)
)

config = STAGE_CONFIG[stage]

DuchessStack(
    app,
    f"DuchessStack-{stage}",
    stage=stage,
    config=config,
    monthly_budget=monthly_budget,
    env=cdk.Environment(account=account, region=region),
    description=(
        f"Duchess Construction Site Intelligence Platform [{stage.upper()}] — "
        f"PPE escalation + batch pipeline"
    ),
)

app.synth()
