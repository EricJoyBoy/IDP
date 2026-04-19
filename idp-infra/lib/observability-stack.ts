import * as cdk from 'aws-cdk-lib';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import { Construct } from 'constructs';

export interface ObservabilityStackProps extends cdk.StackProps {
  /** Tenant identifier — must match the SecurityStack tenantId */
  tenantId: string;
}

/**
 * ObservabilityStack — Task 14.1 & 14.2
 *
 * Provisions:
 *  - CloudWatch Log Groups for every Lambda and ECS service (30-day retention)
 *  - CloudWatch Alarms: pipeline error rate >5% / 5 min, API p95 latency >1500 ms / 1 min
 *  - CloudWatch Dashboard: documents/min, success rate, phase latency widgets
 *  - X-Ray sampling rule for the IDP pipeline (10% sampling rate)
 *
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5
 */
export class ObservabilityStack extends cdk.Stack {

  /** Log groups keyed by service name — exported for cross-stack references */
  public readonly logGroups: Record<string, logs.LogGroup> = {};

  constructor(scope: Construct, id: string, props: ObservabilityStackProps) {
    super(scope, id, props);

    const { tenantId } = props;

    // -------------------------------------------------------------------------
    // Task 14.1 — CloudWatch Log Groups (Req 11.1)
    // One log group per Lambda + one for the ECS Ingestion API
    // -------------------------------------------------------------------------

    const services = [
      'event-router',
      'textract-adapter',
      'nlp-processor',
      'ml-classifier',
      'persistence-handler',
      'status-updater',
      'compensating-transaction',
      'ingestion-api',          // ECS Fargate service
    ];

    for (const svc of services) {
      const logGroup = new logs.LogGroup(this, `LogGroup-${svc}`, {
        logGroupName: `/idp/${tenantId}/${svc}`,
        retention: logs.RetentionDays.ONE_MONTH,   // 30 days — Req 11.1
        removalPolicy: cdk.RemovalPolicy.RETAIN,
      });
      this.logGroups[svc] = logGroup;
    }

    // -------------------------------------------------------------------------
    // Task 14.1 — X-Ray sampling rule for IDP pipeline (Req 11.2)
    // 10% sampling rate keeps costs low while providing representative traces
    // -------------------------------------------------------------------------

    new cdk.CfnResource(this, 'IdpXRaySamplingRule', {
      type: 'AWS::XRay::SamplingRule',
      properties: {
        SamplingRule: {
          RuleName: `idp-${tenantId}-pipeline`,
          Priority: 100,
          FixedRate: 0.10,          // 10% sampling rate
          ReservoirSize: 5,         // minimum 5 traces/sec regardless of rate
          Host: '*',
          HTTPMethod: '*',
          URLPath: '*',
          ResourceARN: `arn:aws:lambda:*:*:function:idp-${tenantId}-*`,
          ServiceName: `idp-${tenantId}-*`,
          ServiceType: '*',
          Version: 1,
        },
      },
    });

    // -------------------------------------------------------------------------
    // Task 14.2 — Custom CloudWatch Metrics (Req 11.5)
    // Metrics are published by CloudWatchMetricsPublisher.java (namespace IDP/Pipeline)
    // Here we reference them for alarms and dashboard widgets.
    // -------------------------------------------------------------------------

    const successMetric = new cloudwatch.Metric({
      namespace: 'IDP/Pipeline',
      metricName: 'ProcessingSuccess',
      dimensionsMap: { TenantId: tenantId },
      statistic: 'Sum',
      period: cdk.Duration.minutes(5),
    });

    const failureMetric = new cloudwatch.Metric({
      namespace: 'IDP/Pipeline',
      metricName: 'ProcessingFailure',
      dimensionsMap: { TenantId: tenantId },
      statistic: 'Sum',
      period: cdk.Duration.minutes(5),
    });

    const documentsProcessedMetric = new cloudwatch.Metric({
      namespace: 'IDP/Pipeline',
      metricName: 'DocumentsProcessed',
      dimensionsMap: { TenantId: tenantId },
      statistic: 'Sum',
      period: cdk.Duration.minutes(1),
    });

    // API latency from API Gateway (p95 over 1 minute) — Req 11.4
    const apiLatencyMetric = new cloudwatch.Metric({
      namespace: 'AWS/ApiGateway',
      metricName: 'Latency',
      dimensionsMap: { ApiName: `idp-${tenantId}-api` },
      statistic: 'p95',
      period: cdk.Duration.minutes(1),
    });

    // Phase latency (average across all phases) — Req 11.5
    const phaseLatencyMetric = new cloudwatch.Metric({
      namespace: 'IDP/Pipeline',
      metricName: 'PhaseDurationMs',
      dimensionsMap: { TenantId: tenantId },
      statistic: 'Average',
      period: cdk.Duration.minutes(5),
    });

    // -------------------------------------------------------------------------
    // Task 14.2 — CloudWatch Alarm: pipeline error rate >5% in 5 min (Req 11.3)
    // Uses metric math: failures / (failures + successes) > 0.05
    // -------------------------------------------------------------------------

    const errorRateExpression = new cloudwatch.MathExpression({
      expression: 'failures / (failures + successes + 0.001)',  // +0.001 avoids division by zero
      usingMetrics: {
        failures: failureMetric,
        successes: successMetric,
      },
      period: cdk.Duration.minutes(5),
      label: 'Pipeline Error Rate',
    });

    new cloudwatch.Alarm(this, 'PipelineErrorRateAlarm', {
      alarmName: `idp-${tenantId}-pipeline-error-rate`,
      alarmDescription: 'Pipeline error rate exceeded 5% over 5 minutes — Req 11.3',
      metric: errorRateExpression,
      threshold: 0.05,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      evaluationPeriods: 1,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });

    // -------------------------------------------------------------------------
    // Task 14.2 — CloudWatch Alarm: API p95 latency >1500 ms in 1 min (Req 11.4)
    // -------------------------------------------------------------------------

    new cloudwatch.Alarm(this, 'ApiLatencyAlarm', {
      alarmName: `idp-${tenantId}-api-latency-p95`,
      alarmDescription: 'API p95 latency exceeded 1500 ms over 1 minute — Req 11.4',
      metric: apiLatencyMetric,
      threshold: 1500,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      evaluationPeriods: 1,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });

    // -------------------------------------------------------------------------
    // Task 14.2 — CloudWatch Dashboard (Req 11.5)
    // Widgets: documents/min, success rate, phase latency
    // -------------------------------------------------------------------------

    new cloudwatch.Dashboard(this, 'IdpDashboard', {
      dashboardName: `idp-${tenantId}-pipeline`,
      widgets: [
        // Row 1: throughput and success rate
        [
          new cloudwatch.GraphWidget({
            title: 'Documents / min',
            left: [documentsProcessedMetric],
            width: 8,
          }),
          new cloudwatch.GraphWidget({
            title: 'Success vs Failure',
            left: [successMetric],
            right: [failureMetric],
            width: 8,
          }),
          new cloudwatch.GraphWidget({
            title: 'Pipeline Error Rate',
            left: [errorRateExpression],
            leftYAxis: { min: 0, max: 1, label: 'Rate (0–1)' },
            width: 8,
          }),
        ],
        // Row 2: latency
        [
          new cloudwatch.GraphWidget({
            title: 'API Latency p95 (ms)',
            left: [apiLatencyMetric],
            leftAnnotations: [{ value: 1500, label: 'SLA 1500 ms', color: '#ff0000' }],
            width: 12,
          }),
          new cloudwatch.GraphWidget({
            title: 'Phase Duration Avg (ms)',
            left: [phaseLatencyMetric],
            width: 12,
          }),
        ],
      ],
    });

    // -------------------------------------------------------------------------
    // Stack outputs
    // -------------------------------------------------------------------------

    new cdk.CfnOutput(this, 'DashboardUrl', {
      value: `https://${this.region}.console.aws.amazon.com/cloudwatch/home#dashboards:name=idp-${tenantId}-pipeline`,
      description: `CloudWatch Dashboard URL for tenant ${tenantId}`,
    });
  }
}
