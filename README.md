# Intelligent Document Processing Platform (IDP)

A production-ready, cloud-native platform for automated processing of financial documents (PDFs, scans, bank statements, reports) on AWS. The system extracts, normalizes, and analyzes data through an event-driven pipeline, exposing results via REST API and an Angular dashboard.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Project Structure](#project-structure)
- [Tech Stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
  - [Backend (Java)](#backend-java)
  - [Frontend (Angular)](#frontend-angular)
  - [Infrastructure (CDK)](#infrastructure-cdk)
- [Configuration](#configuration)
- [API Reference](#api-reference)
- [Pipeline Flow](#pipeline-flow)
- [Security](#security)
- [Observability](#observability)
- [Testing](#testing)
- [CI/CD](#cicd)
- [Multi-Tenant SaaS](#multi-tenant-saas)

---

## Architecture Overview

```
[Angular Frontend]
       │
[API Gateway + Cognito JWT]
       │
[Ingestion API — Spring Boot / ECS Fargate]
       │
[S3 Document Store] ──S3 Event──► [Event Router Lambda]
                                          │
                                  [Step Functions Express Workflow]
                                    │       │       │       │
                               [Textract] [NLP] [ML/KPI] [Persist]
                                          │
                              [DynamoDB + RDS + S3 Data Lake]
```

The pipeline is fully event-driven: an S3 upload triggers a Lambda that starts a Step Functions workflow. Each step is a dedicated Lambda function. Failures are handled via the Saga pattern with compensating transactions and a Dead Letter Queue.

---

## Project Structure

```
.
├── idp-parent/                  # Maven parent POM
├── idp-common/                  # Shared DTOs, models, serialization, observability utils
├── idp-ingestion-api/           # Spring Boot REST API (ECS Fargate)
├── idp-pipeline-lambdas/        # AWS Lambda functions for the processing pipeline
│   ├── eventrouter/             # S3 → Step Functions trigger
│   ├── textract/                # Amazon Textract adapter
│   ├── nlp/                     # Amazon Comprehend NLP processor
│   ├── classifier/              # SageMaker ML classifier + KPI extractor
│   ├── persistence/             # DynamoDB + RDS + S3 Data Lake writer
│   └── status/                  # Status updater + Saga compensating transactions
├── idp-frontend/                # Angular 17 SPA
│   └── src/main/angular/
│       └── src/app/
│           ├── components/
│           │   ├── document-upload/
│           │   ├── document-results/
│           │   ├── tenant-dashboard/
│           │   └── review/
│           └── services/
└── idp-infra/                   # AWS CDK TypeScript infrastructure
    └── lib/
        ├── security-stack.ts    # KMS, S3, RDS, IAM, CloudTrail
        ├── observability-stack.ts # Log groups, X-Ray, alarms, dashboard
        ├── scaling-stack.ts     # Lambda concurrency, ECS auto-scaling, ALB
        └── cicd-stack.ts        # CodePipeline, CodeBuild, rollback
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend API | Java 21, Spring Boot 3.2, ECS Fargate |
| Lambda Functions | Java 21, AWS Lambda |
| Document Extraction | Amazon Textract |
| NLP | Amazon Comprehend |
| ML Classification | Amazon SageMaker |
| Orchestration | AWS Step Functions (Express Workflow) |
| Storage | Amazon S3 (documents + data lake), DynamoDB, RDS PostgreSQL |
| Caching | Amazon ElastiCache (Redis) |
| Auth | Amazon Cognito (OAuth 2.0 / OIDC, JWT) |
| Resilience | Resilience4j (Circuit Breaker, Retry) |
| Frontend | Angular 17 (standalone components), PDF.js |
| Infrastructure | AWS CDK v2 (TypeScript) |
| CI/CD | GitHub Actions + AWS CodePipeline |
| Observability | CloudWatch Logs, X-Ray, CloudWatch Alarms |

---

## Prerequisites

- Java 21+
- Maven 3.9+
- Node.js 18+ and npm
- AWS CLI configured with appropriate credentials
- AWS CDK v2: `npm install -g aws-cdk`

---

## Getting Started

### Backend (Java)

Build all modules from the project root:

```bash
mvn -f idp-parent/pom.xml clean package
```

Run the Ingestion API locally (requires AWS credentials and a running Redis instance):

```bash
cd idp-ingestion-api
mvn spring-boot:run
```

The API starts on `http://localhost:8080`. Set the following environment variables before running:

```bash
export AWS_REGION=eu-west-1
export S3_BUCKET=idp-<tenant-id>-documents
export DYNAMODB_TABLE=idp-<tenant-id>-documents
export REDIS_HOST=localhost
export COGNITO_ISSUER_URI=https://cognito-idp.<region>.amazonaws.com/<user-pool-id>
```

### Frontend (Angular)

```bash
cd idp-frontend/src/main/angular
npm install
npm start          # dev server at http://localhost:4200
npm run build      # production build
npm test           # unit tests (headless Chrome)
```

### Infrastructure (CDK)

```bash
cd idp-infra
npm install
npm run build

# Bootstrap (first time only)
cdk bootstrap

# Deploy all stacks for a tenant
cdk deploy --all \
  --context tenantId=my-tenant \
  --context githubOwner=my-org \
  --context githubRepo=intelligent-document-processing \
  --context githubConnectionArn=arn:aws:codestar-connections:...
```

Individual stacks can be deployed separately:

```bash
cdk deploy IdpSecurityStack-my-tenant
cdk deploy IdpObservabilityStack-my-tenant
cdk deploy IdpScalingStack-my-tenant
cdk deploy IdpCicdStack-my-tenant
```

---

## Configuration

All runtime configuration is managed via environment variables and AWS SSM Parameter Store.

### Key Environment Variables (Ingestion API)

| Variable | Description |
|---|---|
| `AWS_REGION` | AWS region (e.g. `eu-west-1`) |
| `S3_BUCKET` | S3 bucket name for document storage |
| `DYNAMODB_TABLE` | DynamoDB table name |
| `REDIS_HOST` | ElastiCache Redis host |
| `REDIS_PORT` | Redis port (default: `6379`) |
| `CACHE_TTL_SECONDS` | Result cache TTL (default: `300`) |
| `COGNITO_ISSUER_URI` | Cognito JWT issuer URI |
| `IDP_THROTTLING_CAPACITY_LIMIT` | Max concurrent requests (default: `100`) |
| `AWS_SQS_BATCH_QUEUE_URL` | SQS FIFO queue URL for large document batch processing |

### Key Environment Variables (Lambda Functions)

| Variable | Description |
|---|---|
| `STEP_FUNCTIONS_ARN` | Step Functions state machine ARN |
| `DYNAMODB_TABLE` | DynamoDB table name |
| `DLQ_URL` | SQS Dead Letter Queue URL |
| `SAGEMAKER_ENDPOINT_NAME` | SageMaker endpoint for ML classification |
| `DATA_LAKE_BUCKET` | S3 bucket for the data lake |
| `RDS_JDBC_URL` | JDBC URL for RDS PostgreSQL |
| `RDS_USERNAME` / `RDS_PASSWORD` | RDS credentials |

### SSM Parameter Store (per environment)

Parameters are stored under `/idp/{env}/config/` for `dev`, `staging`, and `production` environments.

---

## API Reference

Base URL: `https://<alb-dns>/api/v1`

All endpoints require a valid Cognito JWT in the `Authorization: Bearer <token>` header.

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/documents` | Upload a document (multipart/form-data, field: `file`) |
| `GET` | `/documents/{id}` | Get document processing status |
| `GET` | `/documents/{id}/results` | Get full extraction results (cached) |
| `GET` | `/documents` | List documents for the tenant (paginated) |
| `GET` | `/documents/search` | Search by KPI name, date range, status |
| `PATCH` | `/documents/{id}/review` | Submit manual review corrections |

### Upload Response

```json
{
  "documentId": "uuid",
  "status": "PENDING",
  "uploadTimestamp": "2024-01-15T10:00:00Z"
}
```

### Error Responses

| HTTP Status | Condition |
|---|---|
| `401` | Missing or invalid JWT |
| `403` | Cross-tenant access attempt |
| `413` | File exceeds 50 MB |
| `415` | Unsupported format (accepted: PDF, PNG, JPEG, TIFF) |
| `429` | System at >80% capacity — retry after 30 seconds |

---

## Pipeline Flow

```
S3 ObjectCreated
      │
      ▼
EventRouterHandler        — idempotency check, starts Step Functions
      │
      ▼
TextractAdapterHandler    — AnalyzeDocument (FORMS + TABLES), normalizes to ExtractedContent DTO
      │
      ▼
NLPProcessorHandler       — DetectEntities (orgs, dates, amounts), Circuit Breaker fallback
      │
      ▼
MLClassifierHandler       — SageMaker classification + KPI extraction, Circuit Breaker fallback
      │
      ▼
PersistenceHandler        — DynamoDB (metadata) + RDS (KPIs/classification) + S3 Data Lake
      │
      ▼
DocumentStatusUpdater     — marks document COMPLETED
```

On any step failure after 3 retries (exponential backoff, base 2s, max 60s):

```
CompensatingTransactionHandler  — marks FAILED in DynamoDB, sends to DLQ
DocumentStatusUpdater           — marks document FAILED
```

### Document Statuses

| Status | Meaning |
|---|---|
| `PENDING` | Uploaded, waiting for pipeline |
| `PROCESSING` | Pipeline in progress |
| `COMPLETED` | Successfully processed |
| `FAILED` | Pipeline failed after all retries |
| `NEEDS_REVIEW` | ML confidence < 0.7, manual review required |
| `PERSISTENCE_ERROR` | RDS write failed, data partially persisted |
| `PENDING_BATCH` | Large document (>2 MB) queued for async processing |

---

## Security

- **Authentication**: Amazon Cognito User Pools with OAuth 2.0 / OIDC. Every request requires a valid JWT.
- **Multi-tenant isolation**: `tenant_id` is extracted from the JWT claim and enforced at every layer — S3 key prefix, DynamoDB partition key, RDS row-level, and API responses.
- **Encryption at rest**: AWS KMS CMK per tenant for S3 and RDS.
- **Encryption in transit**: TLS 1.2+ enforced on all connections.
- **Least privilege**: Dedicated IAM roles for each Lambda function and ECS task.
- **Audit trail**: AWS CloudTrail with 90-day retention.

---

## Observability

- **Structured logging**: JSON log entries with fields `document_id`, `tenant_id`, `phase`, `duration_ms`, `status` via `StructuredLogger`.
- **Distributed tracing**: AWS X-Ray traces across all Lambda functions and Step Functions executions.
- **Custom metrics** (namespace `IDP/Pipeline`): `DocumentsProcessed`, `ProcessingSuccess`, `ProcessingFailure`, `PhaseDurationMs`, `EstimatedCostPerDocument`.
- **CloudWatch Alarms**:
  - Pipeline error rate > 5% over 5 minutes
  - API p95 latency > 1500 ms over 1 minute
- **Dashboard**: CloudWatch dashboard with throughput, success rate, phase latency, and API latency widgets.

---

## Testing

Run all Java unit and property-based tests:

```bash
mvn -f idp-parent/pom.xml test
```

Run Angular tests:

```bash
cd idp-frontend/src/main/angular
npm test
```

### Property-Based Tests (jqwik)

The test suite includes formal correctness properties validated with [jqwik](https://jqwik.net/):

| Property | Description |
|---|---|
| Round-trip serialization | `deserialize(serialize(dto)) == dto` for all valid DTOs |
| Tenant isolation invariant | Queries for tenant A never return documents belonging to tenant B |
| Idempotency | Reprocessing the same `document_id` produces the same result without duplicating records |
| Status monotonicity | Document status never regresses from `COMPLETED` or `FAILED` |
| Confidence score range | All ML confidence scores are always in `[0.0, 1.0]` |

---

## CI/CD

The pipeline is defined in `.github/workflows/ci.yml` and `idp-infra/lib/cicd-stack.ts`.

**GitHub Actions** runs on every push and pull request:
1. Maven build and unit tests
2. Angular build and unit tests

**AWS CodePipeline** is triggered on merge to `main`:

```
Source → Build → Unit Tests → Integration Tests → Deploy Staging → Manual Approval → Deploy Production
```

- ECS deployments use rolling updates with zero downtime.
- Lambda functions are deployed via versioned aliases (`LIVE` alias).
- **Automatic rollback**: if the error rate alarm fires within 5 minutes of a deploy, the `LIVE` alias is reverted to the previous Lambda version and ECS triggers a new deployment of the previous task definition.

---

## Multi-Tenant SaaS

The platform is designed for multi-tenant SaaS deployment:

- Each tenant gets isolated AWS resources (KMS key, S3 prefix, DynamoDB partition, RDS rows).
- CDK stacks are parameterized by `tenantId` — deploy one stack set per tenant.
- Tenant-specific ML model versions can be configured per-request via the `modelVersion` input field.
- Per-tenant cache TTL is configurable via `aws.cache.ttl-seconds`.
- SSM Parameter Store paths (`/idp/{env}/config/`) provide isolated configuration per environment.
