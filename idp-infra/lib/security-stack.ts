import * as cdk from 'aws-cdk-lib';
import * as kms from 'aws-cdk-lib/aws-kms';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as cloudtrail from 'aws-cdk-lib/aws-cloudtrail';
import * as logs from 'aws-cdk-lib/aws-logs';
import { Construct } from 'constructs';

export interface SecurityStackProps extends cdk.StackProps {
  /** Tenant identifier — one stack per tenant */
  tenantId: string;
}

export class SecurityStack extends cdk.Stack {
  /** KMS CMK for this tenant — used for S3 and RDS at-rest encryption */
  public readonly tenantKmsKey: kms.Key;

  /** S3 document store bucket with KMS encryption and versioning */
  public readonly documentBucket: s3.Bucket;

  /** RDS instance with KMS encryption and enforced TLS */
  public readonly database: rds.DatabaseInstance;

  /** IAM roles keyed by Lambda/service name */
  public readonly lambdaRoles: Record<string, iam.Role>;

  /** IAM role for ECS Fargate Ingestion API task */
  public readonly ecsTaskRole: iam.Role;

  constructor(scope: Construct, id: string, props: SecurityStackProps) {
    super(scope, id, props);

    const { tenantId } = props;

    // -------------------------------------------------------------------------
    // Task 13.1 — KMS CMK per tenant (Req 14.1, 14.2, 14.5)
    // -------------------------------------------------------------------------

    // One CMK per tenant; automatic annual key rotation satisfies Req 14.5
    // (AWS KMS re-encrypts data keys transparently — no service interruption)
    this.tenantKmsKey = new kms.Key(this, 'TenantKmsKey', {
      alias: `idp/${tenantId}/cmk`,
      description: `IDP CMK for tenant ${tenantId}`,
      enableKeyRotation: true,          // Req 14.5 — automatic rotation, no downtime
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    // -------------------------------------------------------------------------
    // Task 13.1 — S3 document store with KMS at-rest encryption (Req 14.1)
    // -------------------------------------------------------------------------

    // Access-log bucket (no encryption needed for access logs, kept simple)
    const accessLogBucket = new s3.Bucket(this, 'DocumentBucketAccessLogs', {
      bucketName: `idp-${tenantId}-docs-access-logs`,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,
    });

    this.documentBucket = new s3.Bucket(this, 'DocumentBucket', {
      bucketName: `idp-${tenantId}-documents`,
      versioned: true,                  // Req 1.2 — versioning enabled
      encryption: s3.BucketEncryption.KMS,
      encryptionKey: this.tenantKmsKey, // Req 14.1 — tenant CMK
      bucketKeyEnabled: true,           // reduces KMS API calls / cost
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,                 // TLS in transit
      serverAccessLogsBucket: accessLogBucket,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      lifecycleRules: [
        {
          // Move old versions to cheaper storage after 90 days
          noncurrentVersionTransitions: [
            {
              storageClass: s3.StorageClass.INFREQUENT_ACCESS,
              transitionAfter: cdk.Duration.days(90),
            },
          ],
        },
      ],
    });

    // -------------------------------------------------------------------------
    // Task 13.1 — RDS with KMS at-rest encryption + TLS 1.2+ (Req 14.2)
    // -------------------------------------------------------------------------

    // Minimal VPC for RDS (isolated subnets — no internet access)
    const vpc = new ec2.Vpc(this, 'IdpVpc', {
      maxAzs: 2,
      subnetConfiguration: [
        { name: 'isolated', subnetType: ec2.SubnetType.PRIVATE_ISOLATED, cidrMask: 24 },
      ],
    });

    // Parameter group that enforces TLS 1.2+ (rds.force_ssl = 1)
    const rdsParamGroup = new rds.ParameterGroup(this, 'RdsParamGroup', {
      engine: rds.DatabaseInstanceEngine.postgres({
        version: rds.PostgresEngineVersion.VER_15,
      }),
      description: `IDP RDS parameter group — TLS enforced for tenant ${tenantId}`,
      parameters: {
        rds_force_ssl: '1',             // Req 14.2 — enforce TLS in transit
        ssl_min_protocol_version: 'TLSv1.2',
      },
    });

    const dbSecurityGroup = new ec2.SecurityGroup(this, 'DbSecurityGroup', {
      vpc,
      description: `IDP RDS security group for tenant ${tenantId}`,
      allowAllOutbound: false,
    });

    this.database = new rds.DatabaseInstance(this, 'Database', {
      engine: rds.DatabaseInstanceEngine.postgres({
        version: rds.PostgresEngineVersion.VER_15,
      }),
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T3, ec2.InstanceSize.MEDIUM),
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      securityGroups: [dbSecurityGroup],
      storageEncrypted: true,           // Req 14.2 — KMS at-rest encryption
      storageEncryptionKey: this.tenantKmsKey,
      parameterGroup: rdsParamGroup,
      multiAz: true,
      deletionProtection: true,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      databaseName: `idp_${tenantId.replace(/-/g, '_')}`,
    });

    // -------------------------------------------------------------------------
    // Task 13.2 — IAM roles with least-privilege policies (Req 14.3)
    // -------------------------------------------------------------------------

    this.lambdaRoles = {};

    // Helper: create a Lambda execution role with a base policy
    const makeLambdaRole = (name: string, extraPolicies: iam.PolicyStatement[] = []): iam.Role => {
      const role = new iam.Role(this, `${name}Role`, {
        roleName: `idp-${tenantId}-${name.toLowerCase()}`,
        assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
        description: `Least-privilege role for IDP ${name} Lambda (tenant ${tenantId})`,
        managedPolicies: [
          // Basic Lambda execution: CloudWatch Logs + X-Ray
          iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
          iam.ManagedPolicy.fromAwsManagedPolicyName('AWSXRayDaemonWriteAccess'),
        ],
      });
      for (const stmt of extraPolicies) {
        role.addToPolicy(stmt);
      }
      return role;
    };

    // EventRouter — reads S3 events, starts Step Functions, writes DynamoDB, sends to SQS DLQ
    this.lambdaRoles['EventRouter'] = makeLambdaRole('EventRouter', [
      new iam.PolicyStatement({
        sid: 'StartStepFunctions',
        actions: ['states:StartExecution'],
        resources: [`arn:aws:states:${this.region}:${this.account}:stateMachine:idp-${tenantId}-pipeline`],
      }),
      new iam.PolicyStatement({
        sid: 'DynamoDbIdempotency',
        actions: ['dynamodb:GetItem', 'dynamodb:PutItem'],
        resources: [`arn:aws:dynamodb:${this.region}:${this.account}:table/idp-${tenantId}-documents`],
      }),
      new iam.PolicyStatement({
        sid: 'SqsDlq',
        actions: ['sqs:SendMessage'],
        resources: [`arn:aws:sqs:${this.region}:${this.account}:idp-${tenantId}-dlq`],
      }),
    ]);

    // TextractAdapter — reads S3, calls Textract, writes CloudWatch
    this.lambdaRoles['TextractAdapter'] = makeLambdaRole('TextractAdapter', [
      new iam.PolicyStatement({
        sid: 'S3ReadDocument',
        actions: ['s3:GetObject'],
        resources: [`${this.documentBucket.bucketArn}/*`],
      }),
      new iam.PolicyStatement({
        sid: 'KmsDecrypt',
        actions: ['kms:Decrypt', 'kms:GenerateDataKey'],
        resources: [this.tenantKmsKey.keyArn],
      }),
      new iam.PolicyStatement({
        sid: 'TextractAnalyze',
        actions: ['textract:AnalyzeDocument', 'textract:DetectDocumentText'],
        resources: ['*'],               // Textract does not support resource-level ARNs
      }),
    ]);

    // NLPProcessor — calls Comprehend, reads/writes Step Functions task token
    this.lambdaRoles['NLPProcessor'] = makeLambdaRole('NLPProcessor', [
      new iam.PolicyStatement({
        sid: 'ComprehendDetect',
        actions: [
          'comprehend:DetectEntities',
          'comprehend:DetectKeyPhrases',
          'comprehend:DetectSentiment',
        ],
        resources: ['*'],               // Comprehend does not support resource-level ARNs
      }),
    ]);

    // MLClassifier — invokes SageMaker endpoint
    this.lambdaRoles['MLClassifier'] = makeLambdaRole('MLClassifier', [
      new iam.PolicyStatement({
        sid: 'SageMakerInvoke',
        actions: ['sagemaker:InvokeEndpoint'],
        resources: [`arn:aws:sagemaker:${this.region}:${this.account}:endpoint/idp-${tenantId}-classifier`],
      }),
    ]);

    // PersistenceHandler — writes DynamoDB, RDS (via Secrets Manager), S3 data lake
    this.lambdaRoles['PersistenceHandler'] = makeLambdaRole('PersistenceHandler', [
      new iam.PolicyStatement({
        sid: 'DynamoDbWrite',
        actions: ['dynamodb:PutItem', 'dynamodb:UpdateItem', 'dynamodb:GetItem', 'dynamodb:Query'],
        resources: [
          `arn:aws:dynamodb:${this.region}:${this.account}:table/idp-${tenantId}-documents`,
          `arn:aws:dynamodb:${this.region}:${this.account}:table/idp-${tenantId}-documents/index/*`,
        ],
      }),
      new iam.PolicyStatement({
        sid: 'S3DataLakeWrite',
        actions: ['s3:PutObject'],
        resources: [`${this.documentBucket.bucketArn}/datalake/*`],
      }),
      new iam.PolicyStatement({
        sid: 'KmsEncryptDecrypt',
        actions: ['kms:Decrypt', 'kms:GenerateDataKey'],
        resources: [this.tenantKmsKey.keyArn],
      }),
      new iam.PolicyStatement({
        sid: 'SecretsManagerRds',
        actions: ['secretsmanager:GetSecretValue'],
        resources: [`arn:aws:secretsmanager:${this.region}:${this.account}:secret:idp/${tenantId}/rds-*`],
      }),
    ]);

    // StatusUpdater — updates DynamoDB document status
    this.lambdaRoles['StatusUpdater'] = makeLambdaRole('StatusUpdater', [
      new iam.PolicyStatement({
        sid: 'DynamoDbUpdateStatus',
        actions: ['dynamodb:UpdateItem'],
        resources: [`arn:aws:dynamodb:${this.region}:${this.account}:table/idp-${tenantId}-documents`],
      }),
    ]);

    // CompensatingTransaction — rollback writes (DynamoDB delete, S3 delete)
    this.lambdaRoles['CompensatingTransaction'] = makeLambdaRole('CompensatingTransaction', [
      new iam.PolicyStatement({
        sid: 'DynamoDbRollback',
        actions: ['dynamodb:DeleteItem', 'dynamodb:UpdateItem'],
        resources: [`arn:aws:dynamodb:${this.region}:${this.account}:table/idp-${tenantId}-documents`],
      }),
      new iam.PolicyStatement({
        sid: 'S3Rollback',
        actions: ['s3:DeleteObject'],
        resources: [`${this.documentBucket.bucketArn}/*`],
      }),
      new iam.PolicyStatement({
        sid: 'KmsDecrypt',
        actions: ['kms:Decrypt'],
        resources: [this.tenantKmsKey.keyArn],
      }),
    ]);

    // -------------------------------------------------------------------------
    // Task 13.2 — ECS Fargate task role for Ingestion API (Req 14.3)
    // -------------------------------------------------------------------------

    this.ecsTaskRole = new iam.Role(this, 'IngestionApiTaskRole', {
      roleName: `idp-${tenantId}-ingestion-api-task`,
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      description: `Least-privilege ECS task role for IDP Ingestion API (tenant ${tenantId})`,
    });

    // Ingestion API needs: S3 put (upload), DynamoDB put (create record), KMS encrypt
    this.ecsTaskRole.addToPolicy(new iam.PolicyStatement({
      sid: 'S3Upload',
      actions: ['s3:PutObject', 's3:GetObject', 's3:DeleteObject'],
      resources: [`${this.documentBucket.bucketArn}/*`],
    }));
    this.ecsTaskRole.addToPolicy(new iam.PolicyStatement({
      sid: 'DynamoDbDocuments',
      actions: ['dynamodb:PutItem', 'dynamodb:GetItem', 'dynamodb:UpdateItem', 'dynamodb:Query'],
      resources: [
        `arn:aws:dynamodb:${this.region}:${this.account}:table/idp-${tenantId}-documents`,
        `arn:aws:dynamodb:${this.region}:${this.account}:table/idp-${tenantId}-documents/index/*`,
      ],
    }));
    this.ecsTaskRole.addToPolicy(new iam.PolicyStatement({
      sid: 'KmsEncrypt',
      actions: ['kms:Decrypt', 'kms:GenerateDataKey'],
      resources: [this.tenantKmsKey.keyArn],
    }));
    this.ecsTaskRole.addToPolicy(new iam.PolicyStatement({
      sid: 'CloudWatchLogs',
      actions: ['logs:CreateLogStream', 'logs:PutLogEvents'],
      resources: [`arn:aws:logs:${this.region}:${this.account}:log-group:/idp/${tenantId}/*`],
    }));
    this.ecsTaskRole.addToPolicy(new iam.PolicyStatement({
      sid: 'XRay',
      actions: ['xray:PutTraceSegments', 'xray:PutTelemetryRecords'],
      resources: ['*'],
    }));

    // -------------------------------------------------------------------------
    // Task 13.2 — CloudTrail with 90-day retention (Req 14.4)
    // -------------------------------------------------------------------------

    const trailLogBucket = new s3.Bucket(this, 'CloudTrailLogBucket', {
      bucketName: `idp-${tenantId}-cloudtrail-logs`,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,
      encryption: s3.BucketEncryption.KMS,
      encryptionKey: this.tenantKmsKey,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      lifecycleRules: [
        {
          // Req 14.4 — retain logs for at least 90 days; expire after 365 days
          expiration: cdk.Duration.days(365),
          transitions: [
            {
              storageClass: s3.StorageClass.INFREQUENT_ACCESS,
              transitionAfter: cdk.Duration.days(90),
            },
          ],
        },
      ],
    });

    // CloudWatch Log Group for CloudTrail — 90-day retention
    const trailLogGroup = new logs.LogGroup(this, 'CloudTrailLogGroup', {
      logGroupName: `/idp/${tenantId}/cloudtrail`,
      retention: logs.RetentionDays.THREE_MONTHS, // 90 days — Req 14.4
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const trailLogRole = new iam.Role(this, 'CloudTrailLogRole', {
      assumedBy: new iam.ServicePrincipal('cloudtrail.amazonaws.com'),
      description: `CloudTrail → CloudWatch Logs role for tenant ${tenantId}`,
    });
    trailLogRole.addToPolicy(new iam.PolicyStatement({
      actions: ['logs:CreateLogStream', 'logs:PutLogEvents'],
      resources: [trailLogGroup.logGroupArn],
    }));

    new cloudtrail.Trail(this, 'AuditTrail', {
      trailName: `idp-${tenantId}-audit-trail`,
      bucket: trailLogBucket,
      cloudWatchLogGroup: trailLogGroup,
      cloudWatchLogsRetention: logs.RetentionDays.THREE_MONTHS,
      sendToCloudWatchLogs: true,
      cloudWatchLogsRole: trailLogRole,
      includeGlobalServiceEvents: true,
      isMultiRegionTrail: false,
      enableFileValidation: true,       // integrity validation for audit compliance
      encryptionKey: this.tenantKmsKey,
      managementEvents: cloudtrail.ReadWriteType.ALL, // Req 14.4 — all management events
    });

    // -------------------------------------------------------------------------
    // Stack outputs
    // -------------------------------------------------------------------------

    new cdk.CfnOutput(this, 'TenantKmsKeyArn', {
      value: this.tenantKmsKey.keyArn,
      description: `KMS CMK ARN for tenant ${tenantId}`,
    });
    new cdk.CfnOutput(this, 'DocumentBucketName', {
      value: this.documentBucket.bucketName,
      description: `S3 document bucket for tenant ${tenantId}`,
    });
    new cdk.CfnOutput(this, 'DatabaseEndpoint', {
      value: this.database.dbInstanceEndpointAddress,
      description: `RDS endpoint for tenant ${tenantId}`,
    });
  }
}
