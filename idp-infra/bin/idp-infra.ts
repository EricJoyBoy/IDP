#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { SecurityStack } from '../lib/security-stack';
import { ObservabilityStack } from '../lib/observability-stack';
import { ScalingStack } from '../lib/scaling-stack';
import { CicdStack } from '../lib/cicd-stack';

const app = new cdk.App();

// Tenant ID is parameterized — pass via CDK context: --context tenantId=tenant-abc
const tenantId = app.node.tryGetContext('tenantId') ?? 'default';

const env = {
  account: process.env.CDK_DEFAULT_ACCOUNT,
  region: process.env.CDK_DEFAULT_REGION ?? 'eu-west-1',
};

new SecurityStack(app, `IdpSecurityStack-${tenantId}`, {
  tenantId,
  env,
  description: `IDP Security Stack for tenant ${tenantId} — KMS, S3, RDS, IAM, CloudTrail`,
});

new ObservabilityStack(app, `IdpObservabilityStack-${tenantId}`, {
  tenantId,
  env,
  description: `IDP Observability Stack for tenant ${tenantId} — Log Groups, X-Ray, Alarms, Dashboard`,
});

new ScalingStack(app, `IdpScalingStack-${tenantId}`, {
  tenantId,
  env,
  description: `IDP Scaling Stack for tenant ${tenantId} — Lambda concurrency, ECS Fargate auto-scaling, ALB`,
});

new CicdStack(app, `IdpCicdStack-${tenantId}`, {
  tenantId,
  env,
  description: `IDP CI/CD Stack for tenant ${tenantId} — CodePipeline, CodeBuild, rollback, env configs`,
  // Override via CDK context: --context githubOwner=myorg --context githubRepo=idp
  githubConnectionArn: app.node.tryGetContext('githubConnectionArn') ?? 'arn:aws:codestar-connections:eu-west-1:123456789012:connection/placeholder',
  githubOwner: app.node.tryGetContext('githubOwner') ?? 'my-org',
  githubRepo: app.node.tryGetContext('githubRepo') ?? 'intelligent-document-processing',
  githubBranch: app.node.tryGetContext('githubBranch') ?? 'main',
});
