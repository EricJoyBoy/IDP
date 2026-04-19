import * as cdk from 'aws-cdk-lib';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as applicationautoscaling from 'aws-cdk-lib/aws-applicationautoscaling';
import { Construct } from 'constructs';

export interface ScalingStackProps extends cdk.StackProps {
  /** Tenant identifier — must match SecurityStack / ObservabilityStack tenantId */
  tenantId: string;
}

/**
 * ScalingStack — Task 15.1
 *
 * Provisions:
 *  - Reserved concurrency (1000) for each pipeline Lambda (Req 13.1)
 *  - ECS Fargate service for the Ingestion API with ALB (Req 13.1)
 *  - Application Auto Scaling: min=1, max=50 on CPU >70% and memory >80% (Req 13.1)
 *
 * Requirements: 13.1
 */
export class ScalingStack extends cdk.Stack {

  constructor(scope: Construct, id: string, props: ScalingStackProps) {
    super(scope, id, props);

    const { tenantId } = props;

    // -------------------------------------------------------------------------
    // Lambda reserved concurrency — 1000 concurrent executions per function
    // We reference existing Lambda functions by name (deployed separately).
    // CfnFunction.reservedConcurrentExecutions is set via escape hatch on the
    // imported function ARN using a CfnResource so we don't need to redeploy.
    // -------------------------------------------------------------------------

    const lambdaNames = [
      'event-router',
      'textract-adapter',
      'nlp-processor',
      'ml-classifier',
      'persistence-handler',
      'status-updater',
      'compensating-transaction',
    ];

    for (const name of lambdaNames) {
      const fn = lambda.Function.fromFunctionName(
        this,
        `Fn-${name}`,
        `idp-${tenantId}-${name}`,
      );

      // Apply reserved concurrency via CfnFunction override
      const cfnFn = fn.node.defaultChild as lambda.CfnFunction | undefined;
      if (cfnFn) {
        cfnFn.reservedConcurrentExecutions = 1000;
      } else {
        // For imported functions, use a separate ConcurrencyConfig resource
        new lambda.CfnEventInvokeConfig(this, `ConcurrencyConfig-${name}`, {
          functionName: fn.functionName,
          qualifier: '$LATEST',
          maximumRetryAttempts: 2,
        });
        // Set concurrency via low-level CFN resource
        new cdk.CfnResource(this, `ReservedConcurrency-${name}`, {
          type: 'AWS::Lambda::Function',
          properties: {
            FunctionName: fn.functionName,
            ReservedConcurrentExecutions: 1000,
          },
        });
      }
    }

    // -------------------------------------------------------------------------
    // VPC — reuse or create a minimal VPC for ECS Fargate
    // -------------------------------------------------------------------------

    const vpc = new ec2.Vpc(this, 'ScalingVpc', {
      maxAzs: 2,
      subnetConfiguration: [
        {
          name: 'public',
          subnetType: ec2.SubnetType.PUBLIC,
          cidrMask: 24,
        },
        {
          name: 'private',
          subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS,
          cidrMask: 24,
        },
      ],
    });

    // -------------------------------------------------------------------------
    // ECS Cluster
    // -------------------------------------------------------------------------

    const cluster = new ecs.Cluster(this, 'IngestionCluster', {
      clusterName: `idp-${tenantId}-ingestion`,
      vpc,
      containerInsights: true,
    });

    // -------------------------------------------------------------------------
    // Fargate Task Definition for Ingestion API
    // -------------------------------------------------------------------------

    const taskDef = new ecs.FargateTaskDefinition(this, 'IngestionTaskDef', {
      family: `idp-${tenantId}-ingestion-api`,
      cpu: 512,
      memoryLimitMiB: 1024,
    });

    taskDef.addContainer('IngestionApiContainer', {
      image: ecs.ContainerImage.fromRegistry(
        `${this.account}.dkr.ecr.${this.region}.amazonaws.com/idp-${tenantId}-ingestion-api:latest`,
      ),
      portMappings: [{ containerPort: 8080 }],
      logging: ecs.LogDrivers.awsLogs({
        streamPrefix: `idp-${tenantId}-ingestion-api`,
        logRetention: 30,
      }),
      environment: {
        AWS_REGION: this.region,
        TENANT_ID: tenantId,
      },
    });

    // -------------------------------------------------------------------------
    // Application Load Balancer
    // -------------------------------------------------------------------------

    const alb = new elbv2.ApplicationLoadBalancer(this, 'IngestionAlb', {
      loadBalancerName: `idp-${tenantId}-ingestion`,
      vpc,
      internetFacing: true,
    });

    const listener = alb.addListener('HttpListener', {
      port: 80,
      open: true,
    });

    // -------------------------------------------------------------------------
    // ECS Fargate Service — min 1 instance (always-on)
    // -------------------------------------------------------------------------

    const fargateService = new ecs.FargateService(this, 'IngestionService', {
      serviceName: `idp-${tenantId}-ingestion-api`,
      cluster,
      taskDefinition: taskDef,
      desiredCount: 1,
      assignPublicIp: false,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
    });

    listener.addTargets('IngestionTargets', {
      port: 8080,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targets: [fargateService],
      healthCheck: {
        path: '/actuator/health',
        interval: cdk.Duration.seconds(30),
        healthyThresholdCount: 2,
        unhealthyThresholdCount: 3,
      },
    });

    // -------------------------------------------------------------------------
    // Application Auto Scaling — min=1, max=50 (Req 13.1)
    // -------------------------------------------------------------------------

    const scalableTarget = fargateService.autoScaleTaskCount({
      minCapacity: 1,
      maxCapacity: 50,
    });

    // Scale out when CPU > 70%
    scalableTarget.scaleOnCpuUtilization('CpuScaling', {
      targetUtilizationPercent: 70,
      scaleInCooldown: cdk.Duration.seconds(60),
      scaleOutCooldown: cdk.Duration.seconds(30),
    });

    // Scale out when memory > 80%
    scalableTarget.scaleOnMemoryUtilization('MemoryScaling', {
      targetUtilizationPercent: 80,
      scaleInCooldown: cdk.Duration.seconds(60),
      scaleOutCooldown: cdk.Duration.seconds(30),
    });

    // -------------------------------------------------------------------------
    // Stack outputs
    // -------------------------------------------------------------------------

    new cdk.CfnOutput(this, 'AlbDnsName', {
      value: alb.loadBalancerDnsName,
      description: `ALB DNS name for Ingestion API (tenant ${tenantId})`,
    });

    new cdk.CfnOutput(this, 'EcsClusterName', {
      value: cluster.clusterName,
      description: `ECS cluster name for tenant ${tenantId}`,
    });
  }
}
