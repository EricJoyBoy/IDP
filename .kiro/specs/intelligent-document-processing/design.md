# Design Document: Intelligent Document Processing Platform

## Overview

Piattaforma IDP production-ready su AWS per l'elaborazione automatizzata di documenti finanziari. Architettura event-driven serverless-first, multi-tenant, con pipeline orchestrata da AWS Step Functions. Il sistema è composto da microservizi Spring Boot su ECS Fargate, Lambda functions, e servizi AWS managed (Textract, Comprehend, SageMaker).

## Architecture

### High-Level Architecture

```
[Angular Frontend]
       |
[API Gateway + Cognito]
       |
[Ingestion API - Spring Boot / ECS Fargate]
       |
[S3 Document Store] --S3 Event--> [Event Router Lambda]
                                          |
                                  [Step Functions Pipeline]
                                    |    |    |    |    |
                               [Textract][NLP][ML][Persist][Notify]
                                          |
                              [DynamoDB + RDS + S3 Data Lake]
```

### Components

- **Ingestion API**: Spring Boot su ECS Fargate, espone REST API per upload e consultazione
- **Event Router**: Lambda Python/Java, triggered da S3 events, avvia Step Functions
- **Pipeline Orchestrator**: AWS Step Functions Express Workflow
- **Textract Adapter**: Lambda Java, invoca Textract e normalizza output in DTO
- **DTO Normalizer**: Spring Boot microservice, valida DTO via JSON Schema
- **NLP Processor**: Lambda Java, integra Amazon Comprehend con Circuit Breaker
- **ML Classifier**: Lambda Java, invoca SageMaker endpoint per classificazione e KPI
- **Persistence Service**: Lambda Java, scrive su DynamoDB, RDS e S3 Data Lake
- **Frontend**: Angular SPA su S3 + CloudFront

## Data Models

### DocumentDTO

```java
public class DocumentDTO {
    String documentId;          // UUID
    String tenantId;
    String s3Key;
    String format;              // PDF, PNG, JPEG, TIFF
    Long sizeBytes;
    Instant uploadTimestamp;
    DocumentStatus status;      // PENDING, PROCESSING, COMPLETED, FAILED, NEEDS_REVIEW, PERSISTENCE_ERROR
    ExtractedContent content;
    List<Entity> entities;
    ClassificationResult classification;
    List<KPI> kpis;
    Instant processedTimestamp;
}
```

### ExtractedContent

```java
public class ExtractedContent {
    String rawText;
    List<KeyValuePair> keyValuePairs;  // con confidence score
    List<Table> tables;                // righe, colonne, bounding box
    List<BoundingBox> boundingBoxes;
}
```

### ClassificationResult

```java
public class ClassificationResult {
    String category;            // bilancio, conto_economico, rendiconto, contratto, fattura, altro
    Double confidenceScore;
    List<CategoryCandidate> topCandidates;  // top-3 se confidence < 0.7
}
```

### KPI

```java
public class KPI {
    String name;                // ricavi, EBITDA, utile_netto, totale_attivo
    BigDecimal value;
    String unit;
    Double confidenceScore;
}
```

### DynamoDB Schema

- Partition key: `tenant_id#document_id`
- GSI1: `tenant_id` + `status` (query per stato)
- GSI2: `tenant_id` + `document_type` + `upload_date` (query per tipo e data)

## API Design

### Ingestion Endpoints

```
POST   /api/v1/documents                    # Upload documento
GET    /api/v1/documents/{documentId}       # Stato documento
GET    /api/v1/documents/{documentId}/results  # Risultati completi
GET    /api/v1/documents                    # Lista con paginazione
GET    /api/v1/documents/search             # Ricerca per KPI e date range
PATCH  /api/v1/documents/{documentId}/review  # Correzione manuale (NEEDS_REVIEW)
```

### Response: Upload

```json
{
  "documentId": "uuid",
  "status": "PENDING",
  "uploadTimestamp": "ISO-8601"
}
```

## Step Functions Workflow

```
StartExecution
  → TextractStep (retry: 3, backoff: 2s, max: 60s)
  → DTONormalizationStep (retry: 3, backoff: 2s, max: 60s)
  → NLPStep (retry: 3, backoff: 2s, max: 60s) [graceful degradation]
  → MLClassificationStep (retry: 3, backoff: 2s, max: 60s)
  → PersistenceStep (retry: 3, backoff: 2s, max: 60s)
  → UpdateStatusCompleted
  [OnError] → CompensatingTransactions → UpdateStatusFailed → DLQ
```

## Circuit Breaker Pattern

Implementato con Resilience4j per chiamate a Comprehend e SageMaker:
- Threshold: 5 fallimenti consecutivi
- Open duration: 60 secondi
- Half-open: 1 probe request
- Fallback: risultato parziale senza entità NLP

## Multi-Tenant Isolation

- Ogni richiesta porta `tenant_id` nel JWT claim
- S3 key prefix: `{tenant_id}/{document_id}`
- DynamoDB partition key include `tenant_id`
- RDS row-level security per `tenant_id`
- KMS key per tenant per cifratura S3

## Caching Strategy

- ElastiCache Redis per risultati frequenti
- TTL configurabile per tenant
- Cache key: `{tenant_id}:{document_id}:results`
- Header `X-Cache: HIT/MISS` nelle risposte

## Observability

- CloudWatch Logs: JSON strutturato con `document_id`, `tenant_id`, `phase`, `duration_ms`, `status`
- AWS X-Ray: trace distribuiti per ogni esecuzione pipeline
- CloudWatch Metrics custom: documenti/min, success rate, latenza per fase, costo/documento
- CloudWatch Alarms: error rate >5% in 5min, latenza API >1.5s in 1min

## Security

- Cognito User Pools con OAuth 2.0 / OIDC
- JWT validation su ogni richiesta API Gateway
- IAM roles dedicati per ogni Lambda/ECS task (least privilege)
- KMS CMK per tenant per cifratura at-rest
- TLS 1.2+ per transit
- CloudTrail per audit trail (retention 90 giorni)

## CI/CD

- AWS CodePipeline + CodeBuild
- Stages: build → unit test → integration test → deploy staging → manual approval → deploy prod
- Rolling update ECS, versioned Lambda aliases
- Automatic rollback su health check failure entro 5 minuti
- Ambienti: dev, staging, production con configurazioni isolate

## Correctness Properties

### Property 1: Round-trip serialization consistency (Req 4.5)
Per ogni DocumentDTO valido `d`, la deserializzazione della serializzazione JSON di `d` produce un oggetto equivalente a `d`:
```
∀ d: DocumentDTO valid → deserialize(serialize(d)) == d
```

### Property 2: Tenant isolation invariant (Req 10.4)
Per ogni coppia di tenant distinti `t1 ≠ t2`, nessuna query con `tenant_id = t1` restituisce documenti con `tenant_id = t2`:
```
∀ t1, t2: t1 ≠ t2 → query(t1) ∩ documents(t2) = ∅
```

### Property 3: Idempotency of document processing (Req 12.5)
La rielaborazione di un documento con lo stesso `document_id` produce lo stesso risultato finale e non duplica record:
```
∀ doc_id: process(doc_id) ; process(doc_id) → result(doc_id) == result_first_run ∧ count(records, doc_id) == 1
```

### Property 4: Status monotonicity (Req 3.5, 3.2)
Lo stato di un documento segue una progressione monotona: non può tornare a uno stato precedente una volta raggiunto `COMPLETED` o `FAILED`:
```
∀ doc: status(doc) ∈ {COMPLETED, FAILED} → status(doc) never transitions to PENDING or PROCESSING
```

### Property 5: Confidence score range (Req 6.2, 6.3)
Ogni confidence score prodotto dal ML_Classifier è sempre nel range [0.0, 1.0]:
```
∀ result: ClassificationResult → 0.0 ≤ result.confidenceScore ≤ 1.0
∀ kpi: KPI → 0.0 ≤ kpi.confidenceScore ≤ 1.0
```
