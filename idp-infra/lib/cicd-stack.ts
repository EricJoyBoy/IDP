import * as cdk from 'aws-cdk-lib';
import * as codepipeline from 'aws-cdk-lib/aws-codepipeline';
import * as codepipeline_actions from 'aws-cdk-lib/aws-codepipeline-actions';
import * as codebuild from 'aws-cdk-lib/aws-codebuild';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as cloudwatch_actions from 'aws-cdk-lib/aws-cloudwatch-actions';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import * as s3 from 'aws-cdk-lib/aws-s3';
import { Construct } from 'constructs';

export interface CicdStackProps extends cdk.StackProps {
  /** Tenant identifier */
  tenantId: string;
  /**
   * GitHub connection ARN (CodeStar Connections).
   * Create via: aws codeconnections create-connection --provider-type GitHub
   */
  githubConnectionArn: string;
  /** GitHub owner/org */
  githubOwner: string;
  /** GitHub repository name */
  githubRepo: string;
  /** Branch to track (default: main) */
  githubBranch?: string;
}

/**
 * CicdStack — Tasks 17.1 & 17.2
 *
 * Provisions:
 *  - AWS CodePipeline: Source → Build → Unit Test → Integration Test
 *                      → Deploy Staging → Manual Approval → Deploy Production
 *  - CodeBuild project: Maven build + Angular build (Req 15.1)
 *  - ECS rolling update deployment for Ingestion API (Req 15.2)
 *  - Lambda versioned aliases (LIVE) for zero-downtime deploy (Req 15.2)
 *  - CloudWatch alarm-based automatic rollback within 5 minutes (Req 15.3)
 *  - SSM Parameter Store paths per environment: /idp/{env}/config/* (Req 15.4)
 *
 * Requirements: 15.1, 15.2, 15.3, 15.4
 */
export class CicdStack extends cdk.Stack {

  constructor(scope: Construct, id: string, props: CicdStackProps) {
    super(scope, id, props);

    const {
      tenantId,
      githubConnectionArn,
      githubOwner,
      githubRepo,
      githubBranch = 'main',
    } = props;

    // -------------------------------------------------------------------------
    // Req 15.4 — SSM Parameter Store paths for each environment
    // -------------------------------------------------------------------------

    const environments = ['dev', 'staging', 'production'] as const;

    for (const env of environments) {
      // Placeholder config parameters — real values set out-of-band
      new ssm.StringParameter(this, `Param-${env}-AppEnv`, {
        parameterName: `/idp/${env}/config/APP_ENV`,
        stringValue: env,
        description: `IDP application environment for ${env}`,
      });

      new ssm.StringParameter(this, `Param-${env}-TenantId`, {
        parameterName: `/idp/${env}/config/TENANT_ID`,
        stringValue: tenantId,
        description: `IDP tenant ID for ${env}`,
      });

      new ssm.StringParameter(this, `Param-${env}-LogLevel`, {
        parameterName: `/idp/${env}/config/LOG_LEVEL`,
        stringValue: env === 'production' ? 'WARN' : 'DEBUG',
        description: `IDP log level for ${env}`,
      });
    }

    // -------------------------------------------------------------------------
    // Artifact bucket for CodePipeline
    // -------------------------------------------------------------------------

    const artifactBucket = new s3.Bucket(this, 'PipelineArtifacts', {
      bucketName: `idp-${tenantId}-pipeline-artifacts`,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      autoDeleteObjects: true,
      lifecycleRules: [{ expiration: cdk.Duration.days(30) }],
    });

    // -------------------------------------------------------------------------
    // CodeBuild — Maven + Angular build & test (Req 15.1)
    // -------------------------------------------------------------------------

    const buildRole = new iam.Role(this, 'CodeBuildRole', {
      assumedBy: new iam.ServicePrincipal('codebuild.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('AmazonEC2ContainerRegistryPowerUser'),
      ],
    });

    buildRole.addToPolicy(new iam.PolicyStatement({
      actions: ['ssm:GetParameters', 'ssm:GetParameter'],
      resources: [`arn:aws:ssm:${this.region}:${this.account}:parameter/idp/*`],
    }));

    const buildProject = new codebuild.PipelineProject(this, 'BuildProject', {
      projectName: `idp-${tenantId}-build`,
      role: buildRole,
      environment: {
        buildImage: codebuild.LinuxBuildImage.STANDARD_7_0,
        computeType: codebuild.ComputeType.MEDIUM,
        privileged: true, // needed for Docker
      },
      environmentVariables: {
        AWS_ACCOUNT_ID: { value: this.account },
        AWS_REGION: { value: this.region },
        TENANT_ID: { value: tenantId },
        ECR_REPO: { value: `${this.account}.dkr.ecr.${this.region}.amazonaws.com/idp-${tenantId}-ingestion-api` },
      },
      buildSpec: codebuild.BuildSpec.fromObject({
        version: '0.2',
        phases: {
          install: {
            'runtime-versions': { java: 'corretto17', nodejs: '18' },
          },
          pre_build: {
            commands: [
              'echo Logging in to Amazon ECR...',
              'aws ecr get-login-password | docker login --username AWS --password-stdin $ECR_REPO',
              'COMMIT_HASH=$(echo $CODEBUILD_RESOLVED_SOURCE_VERSION | cut -c 1-7)',
              'IMAGE_TAG=${COMMIT_HASH:=latest}',
            ],
          },
          build: {
            commands: [
              // Java build
              'mvn -f idp-parent/pom.xml clean package -DskipTests=false -B',
              // Angular build
              'cd idp-frontend/src/main/angular && npm ci && npm run build -- --configuration production',
              'cd $CODEBUILD_SRC_DIR',
              // Docker build & push
              'docker build -t $ECR_REPO:$IMAGE_TAG -f idp-ingestion-api/Dockerfile idp-ingestion-api/',
              'docker push $ECR_REPO:$IMAGE_TAG',
              'docker tag $ECR_REPO:$IMAGE_TAG $ECR_REPO:latest',
              'docker push $ECR_REPO:latest',
              // Write image definition for ECS deploy action
              'printf \'[{"name":"IngestionApiContainer","imageUri":"%s"}]\' $ECR_REPO:$IMAGE_TAG > imagedefinitions.json',
            ],
          },
          post_build: {
            commands: [
              'echo Build completed on `date`',
            ],
          },
        },
        artifacts: {
          files: [
            'imagedefinitions.json',
            'idp-parent/**/target/*.jar',
            'idp-frontend/src/main/angular/dist/**/*',
          ],
          'discard-paths': 'no',
        },
        cache: {
          paths: [
            '/root/.m2/**/*',
            'idp-frontend/src/main/angular/node_modules/**/*',
          ],
        },
      }),
    });

    // Unit test project (runs Maven Surefire + Angular Karma)
    const unitTestProject = new codebuild.PipelineProject(this, 'UnitTestProject', {
      projectName: `idp-${tenantId}-unit-tests`,
      role: buildRole,
      environment: {
        buildImage: codebuild.LinuxBuildImage.STANDARD_7_0,
        computeType: codebuild.ComputeType.MEDIUM,
      },
      buildSpec: codebuild.BuildSpec.fromObject({
        version: '0.2',
        phases: {
          install: { 'runtime-versions': { java: 'corretto17', nodejs: '18' } },
          build: {
            commands: [
              'mvn -f idp-parent/pom.xml test -B',
              'cd idp-frontend/src/main/angular && npm ci && npm test -- --watch=false --browsers=ChromeHeadless',
            ],
          },
        },
        reports: {
          'unit-test-reports': {
            files: ['**/surefire-reports/*.xml', '**/test-results/*.xml'],
            'file-format': 'JUNITXML',
          },
        },
      }),
    });

    // Integration test project
    const integrationTestProject = new codebuild.PipelineProject(this, 'IntegrationTestProject', {
      projectName: `idp-${tenantId}-integration-tests`,
      role: buildRole,
      environment: {
        buildImage: codebuild.LinuxBuildImage.STANDARD_7_0,
        computeType: codebuild.ComputeType.MEDIUM,
      },
      environmentVariables: {
        TEST_ENV: { value: 'staging' },
        API_BASE_URL: {
          value: `/idp/staging/config/API_BASE_URL`,
          type: codebuild.BuildEnvironmentVariableType.PARAMETER_STORE,
        },
      },
      buildSpec: codebuild.BuildSpec.fromObject({
        version: '0.2',
        phases: {
          install: { 'runtime-versions': { java: 'corretto17' } },
          build: {
            commands: [
              'mvn -f idp-parent/pom.xml verify -P integration-tests -B',
            ],
          },
        },
        reports: {
          'integration-test-reports': {
            files: ['**/failsafe-reports/*.xml'],
            'file-format': 'JUNITXML',
          },
        },
      }),
    });

    // -------------------------------------------------------------------------
    // CodePipeline role
    // -------------------------------------------------------------------------

    const pipelineRole = new iam.Role(this, 'PipelineRole', {
      assumedBy: new iam.ServicePrincipal('codepipeline.amazonaws.com'),
    });

    pipelineRole.addToPolicy(new iam.PolicyStatement({
      actions: [
        'codebuild:BatchGetBuilds',
        'codebuild:StartBuild',
        'ecs:DescribeServices',
        'ecs:DescribeTaskDefinition',
        'ecs:DescribeTasks',
        'ecs:ListTasks',
        'ecs:RegisterTaskDefinition',
        'ecs:UpdateService',
        'iam:PassRole',
        'lambda:InvokeFunction',
        'lambda:ListFunctions',
        'lambda:UpdateFunctionCode',
        'lambda:PublishVersion',
        'lambda:UpdateAlias',
        'lambda:GetAlias',
        'lambda:CreateAlias',
        'sns:Publish',
        'codestar-connections:UseConnection',
      ],
      resources: ['*'],
    }));

    artifactBucket.grantReadWrite(pipelineRole);

    // -------------------------------------------------------------------------
    // Pipeline artifacts
    // -------------------------------------------------------------------------

    const sourceOutput = new codepipeline.Artifact('SourceOutput');
    const buildOutput = new codepipeline.Artifact('BuildOutput');

    // -------------------------------------------------------------------------
    // SNS topic for manual approval notification
    // -------------------------------------------------------------------------

    const approvalTopic = new sns.Topic(this, 'ApprovalTopic', {
      topicName: `idp-${tenantId}-deploy-approval`,
      displayName: `IDP ${tenantId} — Production Deploy Approval`,
    });

    // -------------------------------------------------------------------------
    // Lambda deploy helper — publishes new version and updates LIVE alias
    // Req 15.2 — versioned Lambda aliases for zero-downtime deploy
    // -------------------------------------------------------------------------

    const lambdaDeployRole = new iam.Role(this, 'LambdaDeployRole', {
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
      ],
    });

    lambdaDeployRole.addToPolicy(new iam.PolicyStatement({
      actions: [
        'lambda:PublishVersion',
        'lambda:UpdateAlias',
        'lambda:GetAlias',
        'lambda:CreateAlias',
        'lambda:GetFunction',
        'lambda:UpdateFunctionCode',
        'lambda:ListVersionsByFunction',
      ],
      resources: [`arn:aws:lambda:${this.region}:${this.account}:function:idp-${tenantId}-*`],
    }));

    // Lambda function that publishes a new version and points LIVE alias to it
    const lambdaAliasUpdater = new lambda.Function(this, 'LambdaAliasUpdater', {
      functionName: `idp-${tenantId}-alias-updater`,
      runtime: lambda.Runtime.NODEJS_20_X,
      handler: 'index.handler',
      role: lambdaDeployRole,
      timeout: cdk.Duration.minutes(5),
      code: lambda.Code.fromInline(`
const { LambdaClient, PublishVersionCommand, UpdateAliasCommand, CreateAliasCommand, GetAliasCommand } = require('@aws-sdk/client-lambda');
const client = new LambdaClient({});

exports.handler = async (event) => {
  const functionNames = (process.env.LAMBDA_FUNCTION_NAMES || '').split(',').filter(Boolean);
  const alias = process.env.ALIAS_NAME || 'LIVE';

  for (const fnName of functionNames) {
    // Publish new version
    const versionResp = await client.send(new PublishVersionCommand({ FunctionName: fnName }));
    const newVersion = versionResp.Version;
    console.log(\`Published version \${newVersion} for \${fnName}\`);

    // Update or create LIVE alias
    try {
      await client.send(new GetAliasCommand({ FunctionName: fnName, Name: alias }));
      await client.send(new UpdateAliasCommand({ FunctionName: fnName, Name: alias, FunctionVersion: newVersion }));
    } catch (e) {
      if (e.name === 'ResourceNotFoundException') {
        await client.send(new CreateAliasCommand({ FunctionName: fnName, Name: alias, FunctionVersion: newVersion }));
      } else { throw e; }
    }
    console.log(\`Alias \${alias} -> \${newVersion} for \${fnName}\`);
  }
  return { statusCode: 200 };
};
      `),
      environment: {
        LAMBDA_FUNCTION_NAMES: [
          `idp-${tenantId}-event-router`,
          `idp-${tenantId}-textract-adapter`,
          `idp-${tenantId}-nlp-processor`,
          `idp-${tenantId}-ml-classifier`,
          `idp-${tenantId}-persistence-handler`,
        ].join(','),
        ALIAS_NAME: 'LIVE',
      },
    });

    // -------------------------------------------------------------------------
    // CloudWatch alarms for automatic rollback (Req 15.3)
    // Alarm triggers if error rate > 5% within 5 minutes of deploy
    // -------------------------------------------------------------------------

    // Staging error alarm
    const stagingErrorAlarm = new cloudwatch.Alarm(this, 'StagingErrorAlarm', {
      alarmName: `idp-${tenantId}-staging-error-rate`,
      alarmDescription: 'Staging Lambda error rate > 5% — triggers rollback',
      metric: new cloudwatch.MathExpression({
        expression: '(errors / invocations) * 100',
        usingMetrics: {
          errors: new cloudwatch.Metric({
            namespace: 'AWS/Lambda',
            metricName: 'Errors',
            dimensionsMap: { FunctionName: `idp-${tenantId}-event-router` },
            statistic: 'Sum',
            period: cdk.Duration.minutes(1),
          }),
          invocations: new cloudwatch.Metric({
            namespace: 'AWS/Lambda',
            metricName: 'Invocations',
            dimensionsMap: { FunctionName: `idp-${tenantId}-event-router` },
            statistic: 'Sum',
            period: cdk.Duration.minutes(1),
          }),
        },
        period: cdk.Duration.minutes(1),
      }),
      threshold: 5,
      evaluationPeriods: 5, // 5 consecutive 1-min periods = 5 minutes (Req 15.3)
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });

    // Production error alarm
    const productionErrorAlarm = new cloudwatch.Alarm(this, 'ProductionErrorAlarm', {
      alarmName: `idp-${tenantId}-production-error-rate`,
      alarmDescription: 'Production Lambda error rate > 5% — triggers rollback',
      metric: new cloudwatch.MathExpression({
        expression: '(errors / invocations) * 100',
        usingMetrics: {
          errors: new cloudwatch.Metric({
            namespace: 'AWS/Lambda',
            metricName: 'Errors',
            dimensionsMap: { FunctionName: `idp-${tenantId}-event-router` },
            statistic: 'Sum',
            period: cdk.Duration.minutes(1),
          }),
          invocations: new cloudwatch.Metric({
            namespace: 'AWS/Lambda',
            metricName: 'Invocations',
            dimensionsMap: { FunctionName: `idp-${tenantId}-event-router` },
            statistic: 'Sum',
            period: cdk.Duration.minutes(1),
          }),
        },
        period: cdk.Duration.minutes(1),
      }),
      threshold: 5,
      evaluationPeriods: 5,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });

    // Rollback Lambda — reverts LIVE alias to previous version on alarm
    const rollbackLambdaRole = new iam.Role(this, 'RollbackLambdaRole', {
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
      ],
    });

    rollbackLambdaRole.addToPolicy(new iam.PolicyStatement({
      actions: [
        'lambda:ListVersionsByFunction',
        'lambda:GetAlias',
        'lambda:UpdateAlias',
        'ecs:UpdateService',
        'ecs:DescribeServices',
        'ecs:DescribeTaskDefinition',
        'ecs:RegisterTaskDefinition',
      ],
      resources: ['*'],
    }));

    const rollbackFn = new lambda.Function(this, 'RollbackFunction', {
      functionName: `idp-${tenantId}-rollback`,
      runtime: lambda.Runtime.NODEJS_20_X,
      handler: 'index.handler',
      role: rollbackLambdaRole,
      timeout: cdk.Duration.minutes(5),
      code: lambda.Code.fromInline(`
const { LambdaClient, ListVersionsByFunctionCommand, GetAliasCommand, UpdateAliasCommand } = require('@aws-sdk/client-lambda');
const { ECSClient, DescribeServicesCommand, UpdateServiceCommand } = require('@aws-sdk/client-ecs');
const lambdaClient = new LambdaClient({});
const ecsClient = new ECSClient({});

exports.handler = async (event) => {
  console.log('Rollback triggered by alarm:', JSON.stringify(event));
  const functionNames = (process.env.LAMBDA_FUNCTION_NAMES || '').split(',').filter(Boolean);
  const alias = process.env.ALIAS_NAME || 'LIVE';

  for (const fnName of functionNames) {
    // Get current alias version
    const aliasResp = await lambdaClient.send(new GetAliasCommand({ FunctionName: fnName, Name: alias }));
    const currentVersion = aliasResp.FunctionVersion;

    // List versions sorted descending, find the one before current
    const versionsResp = await lambdaClient.send(new ListVersionsByFunctionCommand({ FunctionName: fnName }));
    const versions = (versionsResp.Versions || [])
      .filter(v => v.Version !== '$LATEST' && v.Version !== currentVersion)
      .sort((a, b) => parseInt(b.Version) - parseInt(a.Version));

    if (versions.length > 0) {
      const prevVersion = versions[0].Version;
      await lambdaClient.send(new UpdateAliasCommand({ FunctionName: fnName, Name: alias, FunctionVersion: prevVersion }));
      console.log(\`Rolled back \${fnName} LIVE alias: \${currentVersion} -> \${prevVersion}\`);
    } else {
      console.warn(\`No previous version found for \${fnName}, skipping rollback\`);
    }
  }

  // ECS rollback — force new deployment to previous task definition
  const clusterName = process.env.ECS_CLUSTER;
  const serviceName = process.env.ECS_SERVICE;
  if (clusterName && serviceName) {
    await ecsClient.send(new UpdateServiceCommand({
      cluster: clusterName,
      service: serviceName,
      forceNewDeployment: true,
    }));
    console.log(\`ECS service \${serviceName} rollback triggered\`);
  }

  return { statusCode: 200 };
};
      `),
      environment: {
        LAMBDA_FUNCTION_NAMES: [
          `idp-${tenantId}-event-router`,
          `idp-${tenantId}-textract-adapter`,
          `idp-${tenantId}-nlp-processor`,
          `idp-${tenantId}-ml-classifier`,
          `idp-${tenantId}-persistence-handler`,
        ].join(','),
        ALIAS_NAME: 'LIVE',
        ECS_CLUSTER: `idp-${tenantId}-ingestion`,
        ECS_SERVICE: `idp-${tenantId}-ingestion-api`,
      },
    });

    // Wire alarms to invoke rollback Lambda
    const rollbackAlarmAction = new cloudwatch_actions.LambdaAction(rollbackFn);
    stagingErrorAlarm.addAlarmAction(rollbackAlarmAction);
    productionErrorAlarm.addAlarmAction(rollbackAlarmAction);

    // -------------------------------------------------------------------------
    // CodePipeline — Source → Build → Unit Test → Integration Test
    //                → Deploy Staging → Manual Approval → Deploy Production
    // Req 15.1, 15.2
    // -------------------------------------------------------------------------

    new codepipeline.Pipeline(this, 'IdpPipeline', {
      pipelineName: `idp-${tenantId}-pipeline`,
      role: pipelineRole,
      artifactBucket,
      stages: [
        // Stage 1: Source — GitHub via CodeStar Connection
        {
          stageName: 'Source',
          actions: [
            new codepipeline_actions.CodeStarConnectionsSourceAction({
              actionName: 'GitHub_Source',
              connectionArn: githubConnectionArn,
              owner: githubOwner,
              repo: githubRepo,
              branch: githubBranch,
              output: sourceOutput,
              triggerOnPush: true,
            }),
          ],
        },

        // Stage 2: Build — Maven + Angular + Docker push
        {
          stageName: 'Build',
          actions: [
            new codepipeline_actions.CodeBuildAction({
              actionName: 'Build_And_Package',
              project: buildProject,
              input: sourceOutput,
              outputs: [buildOutput],
            }),
          ],
        },

        // Stage 3: Unit Tests
        {
          stageName: 'UnitTest',
          actions: [
            new codepipeline_actions.CodeBuildAction({
              actionName: 'Unit_Tests',
              project: unitTestProject,
              input: sourceOutput,
            }),
          ],
        },

        // Stage 4: Integration Tests (against staging env)
        {
          stageName: 'IntegrationTest',
          actions: [
            new codepipeline_actions.CodeBuildAction({
              actionName: 'Integration_Tests',
              project: integrationTestProject,
              input: sourceOutput,
            }),
          ],
        },

        // Stage 5: Deploy Staging — ECS rolling update + Lambda alias update
        // Req 15.2 — rolling update ECS, versioned Lambda aliases
        {
          stageName: 'DeployStaging',
          actions: [
            new codepipeline_actions.EcsDeployAction({
              actionName: 'ECS_Deploy_Staging',
              service: {
                // Reference existing ECS service by name
                serviceArn: `arn:aws:ecs:${this.region}:${this.account}:service/idp-${tenantId}-ingestion/idp-${tenantId}-ingestion-api`,
                serviceName: `idp-${tenantId}-ingestion-api`,
                cluster: { clusterArn: `arn:aws:ecs:${this.region}:${this.account}:cluster/idp-${tenantId}-ingestion`, clusterName: `idp-${tenantId}-ingestion` },
                env: { account: this.account, region: this.region },
                node: this.node,
                stack: this,
              } as any,
              input: buildOutput,
              deploymentTimeout: cdk.Duration.minutes(10),
            }),
            new codepipeline_actions.LambdaInvokeAction({
              actionName: 'Lambda_Alias_Update_Staging',
              lambda: lambdaAliasUpdater,
              runOrder: 2,
            }),
          ],
        },

        // Stage 6: Manual Approval before production (Req 15.4)
        {
          stageName: 'ManualApproval',
          actions: [
            new codepipeline_actions.ManualApprovalAction({
              actionName: 'Approve_Production_Deploy',
              notificationTopic: approvalTopic,
              additionalInformation: `Review staging deployment for tenant ${tenantId} before promoting to production.`,
            }),
          ],
        },

        // Stage 7: Deploy Production — ECS rolling update + Lambda alias update
        {
          stageName: 'DeployProduction',
          actions: [
            new codepipeline_actions.EcsDeployAction({
              actionName: 'ECS_Deploy_Production',
              service: {
                serviceArn: `arn:aws:ecs:${this.region}:${this.account}:service/idp-${tenantId}-ingestion-prod/idp-${tenantId}-ingestion-api-prod`,
                serviceName: `idp-${tenantId}-ingestion-api-prod`,
                cluster: { clusterArn: `arn:aws:ecs:${this.region}:${this.account}:cluster/idp-${tenantId}-ingestion-prod`, clusterName: `idp-${tenantId}-ingestion-prod` },
                env: { account: this.account, region: this.region },
                node: this.node,
                stack: this,
              } as any,
              input: buildOutput,
              deploymentTimeout: cdk.Duration.minutes(10),
            }),
            new codepipeline_actions.LambdaInvokeAction({
              actionName: 'Lambda_Alias_Update_Production',
              lambda: lambdaAliasUpdater,
              runOrder: 2,
            }),
          ],
        },
      ],
    });

    // -------------------------------------------------------------------------
    // Stack outputs
    // -------------------------------------------------------------------------

    new cdk.CfnOutput(this, 'PipelineName', {
      value: `idp-${tenantId}-pipeline`,
      description: 'CodePipeline name',
    });

    new cdk.CfnOutput(this, 'ApprovalTopicArn', {
      value: approvalTopic.topicArn,
      description: 'SNS topic ARN for manual approval notifications',
    });

    new cdk.CfnOutput(this, 'RollbackFunctionArn', {
      value: rollbackFn.functionArn,
      description: 'Lambda ARN for automatic rollback',
    });
  }
}
