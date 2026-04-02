#!/usr/bin/env python3
"""
Duchess Cloud — CDK App Entry Point.

Deploys the PPE escalation pipeline and nightly batch infrastructure.
"""

import aws_cdk as cdk

from stacks.duchess_stack import DuchessStack

app = cdk.App()

DuchessStack(
    app,
    "DuchessStack",
    env=cdk.Environment(
        account=app.node.try_get_context("account"),
        region=app.node.try_get_context("region") or "us-east-1",
    ),
    description="Duchess Construction Site Intelligence Platform — PPE escalation + batch pipeline",
)

app.synth()
