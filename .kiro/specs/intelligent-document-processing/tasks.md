# Implementation Plan: Intelligent Document Processing Platform

## Overview

Piano di implementazione incrementale della piattaforma IDP su AWS. Ogni task costruisce sulle fondamenta del precedente, partendo dalle strutture dati core fino all'integrazione completa dei componenti.

## Tasks

- [x] 1. Setup progetto e strutture dati core
  - Creare struttura Maven multi-module: `idp-parent`, `idp-common`, `idp-ingestion-api`, `idp-pipeline-lambdas`, `idp-frontend`
  - Definire le classi Java `DocumentDTO`, `ExtractedContent`, `KeyValuePair`, `Table`, `BoundingBox`, `ClassificationResult`, `KPI`, `Entity`, `DocumentStatus` nel modulo `idp-common`
  - Implementare serializzazione/deserializzazione JSON con Jackson e validazione JSON Schema
  - Configurare dipendenze Spring Boot, AWS SDK v2, Resilience4j, Jackson nel `pom.xml`
  - _Requirements: 4.2, 4.4_

  - [ ]* 1.1 Scrivere property test per round-trip serialization dei DTO
    - **Property 1: Round-trip serialization consistency**
    - **Validates: Requirements 4.5**
    - Usare jqwik per generare `DocumentDTO` arbitrari e verificare `deserialize(serialize(d)) == d`

- [x] 2. Ingestion API - Upload documenti
  - Creare controller Spring Boot `DocumentIngestionController` con endpoint `POST /api/v1/documents`
  - Implementare validazione formato (PDF, PNG, JPEG, TIFF → HTTP 415) e dimensione (>50MB → HTTP 413)
  - Implementare upload su S3 con metadata `tenant_id`, `timestamp`, `format` usando AWS SDK v2 `S3Client`
  - Supportare multipart upload per file >5MB con gestione sessione fino a 24 ore
  - Restituire `document_id` UUID entro 2 secondi
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

  - [ ]* 2.1 Scrivere unit test per validazione upload
    - Testare rifiuto formati non supportati (HTTP 415)
    - Testare rifiuto file >50MB (HTTP 413)
    - Testare risposta con `document_id` per upload valido
    - _Requirements: 1.3, 1.4_

- [x] 3. Ingestion API - Endpoint di consultazione
  - Implementare `GET /api/v1/documents/{documentId}` per stato documento
  - Implementare `GET /api/v1/documents/{documentId}/results` per risultati completi
  - Implementare `GET /api/v1/documents` con paginazione per lista documenti del tenant
  - Implementare `GET /api/v1/documents/search` con filtri KPI e date range
  - Applicare isolamento tenant: HTTP 403 se `document_id` appartiene a tenant diverso
  - _Requirements: 8.1, 8.2, 8.3_

  - [x] 3.1 Implementare caching con ElastiCache Redis
    - Configurare `RedisTemplate` con TTL configurabile per tenant
    - Aggiungere header `X-Cache: HIT/MISS` nelle risposte
    - Cache key: `{tenant_id}:{document_id}:results`
    - _Requirements: 8.4, 8.5_

  - [ ]* 3.2 Scrivere unit test per isolamento tenant nelle API di consultazione
    - Verificare HTTP 403 per accesso cross-tenant senza rivelare esistenza documento
    - _Requirements: 8.3_

  - [ ]* 3.3 Scrivere unit test per caching Redis
    - Verificare header `X-Cache: HIT` e latenza <100ms su cache hit
    - _Requirements: 8.5_

- [x] 4. Autenticazione e autorizzazione
  - Configurare Amazon Cognito User Pool con supporto username/password e OAuth 2.0 / OIDC (CDK)
  - Implementare JWT filter Spring Security che valida token Cognito e estrae `tenant_id`, `user_id`, `roles`
  - Configurare API Gateway authorizer Lambda per validazione JWT (HTTP 401 per token assente/scaduto/invalido)
  - Implementare `TenantContext` ThreadLocal per propagare `tenant_id` in tutti i layer applicativi
  - Applicare HTTP 403 per operazioni non consentite dal ruolo
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

  - [ ]* 4.1 Scrivere property test per tenant isolation invariant
    - **Property 2: Tenant isolation invariant**
    - **Validates: Requirements 10.4**
    - Verificare che query con `tenant_id=t1` non restituisca mai documenti con `tenant_id=t2` per t1≠t2

- [x] 5. Event Router Lambda
  - Creare Lambda Java `EventRouterHandler` triggered da eventi S3 `ObjectCreated`
  - Estrarre `document_id`, `tenant_id`, percorso S3 e timestamp dall'evento S3
  - Avviare esecuzione Step Functions entro 5 secondi dall'evento, passando il payload completo
  - Implementare idempotenza: verificare su DynamoDB se esiste già un'esecuzione attiva per `document_id`
  - Gestire fallimento Step Functions: inviare messaggio a DLQ e loggare su CloudWatch
  - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [ ]* 5.1 Scrivere unit test per idempotenza Event Router
    - Verificare che due eventi S3 con stesso `document_id` non avviino due esecuzioni
    - _Requirements: 2.4_

- [x] 6. Textract Adapter Lambda
  - Creare Lambda Java `TextractAdapterHandler` che invoca Amazon Textract (`AnalyzeDocument` API)
  - Implementare parsing dell'output Textract: estrarre testo, key-value pairs con confidence, tabelle con righe/colonne e bounding box
  - Ricombinare righe di tabelle multi-pagina in struttura coerente
  - Normalizzare output in `ExtractedContent` DTO e validare via JSON Schema
  - Gestire errori Textract: loggare su CloudWatch e segnalare fallimento al Pipeline_Orchestrator
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.6_

  - [ ]* 6.1 Scrivere property test per round-trip DTO Textract
    - **Property 1: Round-trip serialization consistency**
    - **Validates: Requirements 4.5**
    - Generare `ExtractedContent` arbitrari con jqwik e verificare consistenza serializzazione

  - [ ]* 6.2 Scrivere unit test per ricombinazione tabelle multi-pagina
    - Testare merge di righe distribuite su più pagine
    - _Requirements: 4.6_

- [x] 7. NLP Processor Lambda
  - Creare Lambda Java `NLPProcessorHandler` che invoca Amazon Comprehend (`DetectEntities` API)
  - Estrarre entità: organizzazioni, date, importi monetari, percentuali con tipo, valore, posizione e confidence score
  - Arricchire il DTO con le entità riconosciute
  - Implementare Circuit Breaker con Resilience4j (5 fallimenti → open 60s)
  - Quando Circuit Breaker è aperto: restituire risultato parziale senza entità, segnalare degradazione al Pipeline_Orchestrator, continuare pipeline
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

  - [ ]* 7.1 Scrivere unit test per Circuit Breaker NLP
    - Verificare apertura circuito dopo 5 fallimenti consecutivi
    - Verificare fallback con risultato parziale senza bloccare la pipeline
    - _Requirements: 5.3, 5.4_

- [x] 8. ML Classifier Lambda
  - Creare Lambda Java `MLClassifierHandler` che invoca SageMaker endpoint per classificazione
  - Classificare documento in: bilancio, conto_economico, rendiconto, contratto, fattura, altro
  - Estrarre KPI finanziari per categoria (ricavi, EBITDA, utile_netto, totale_attivo) con valore, unità e confidence score
  - Se confidence < 0.7: marcare documento `NEEDS_REVIEW` e includere top-3 categorie candidate
  - Supportare versione modello configurabile per tenant senza riavvio servizio
  - Rispettare SLA 10 secondi per documenti fino a 20 pagine
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

  - [ ]* 8.1 Scrivere property test per confidence score range
    - **Property 5: Confidence score range**
    - **Validates: Requirements 6.2, 6.3**
    - Verificare con jqwik che tutti i confidence score siano in [0.0, 1.0] per qualsiasi input

  - [ ]* 8.2 Scrivere unit test per logica NEEDS_REVIEW
    - Testare marcatura `NEEDS_REVIEW` quando confidence < 0.7
    - Verificare presenza top-3 candidati con rispettivi score
    - _Requirements: 6.3_

- [x] 9. Checkpoint - Verificare integrazione Lambda pipeline
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Step Functions Pipeline Orchestrator
  - Definire AWS Step Functions Express Workflow in ASL (Amazon States Language)
  - Configurare sequenza: TextractStep → DTONormalizationStep → NLPStep → MLClassificationStep → PersistenceStep → UpdateStatusCompleted
  - Configurare retry con backoff esponenziale (base 2s, max 60s, max 3 tentativi) per ogni step
  - Implementare Saga pattern: in caso di fallimento irreversibile, eseguire compensating transactions e aggiornare stato a `FAILED`
  - Inviare notifica a DLQ quando documento marcato `FAILED`
  - Aggiornare stato `COMPLETED` nel Metadata_Store entro 1 secondo dal completamento
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

  - [ ]* 10.1 Scrivere property test per status monotonicity
    - **Property 4: Status monotonicity**
    - **Validates: Requirements 3.5, 3.2**
    - Verificare che lo stato non regredisca da COMPLETED/FAILED a stati precedenti

- [x] 11. Persistence Service Lambda
  - Creare Lambda Java `PersistenceHandler` per scrittura risultati
  - Scrivere `DocumentDTO` su DynamoDB con chiave `tenant_id#document_id` e GSI per query per stato, tipo, data
  - Replicare KPI e classificazioni su RDS in modo transazionale
  - Archiviare documento originale, DTO grezzo e risultati finali su S3 Data Lake con partizione `tenant_id/year/month/day`
  - Gestire fallimento RDS: rollback transazione e marcare documento `PERSISTENCE_ERROR` nel Metadata_Store
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [ ]* 11.1 Scrivere property test per idempotency of document processing
    - **Property 3: Idempotency of document processing**
    - **Validates: Requirements 12.5**
    - Verificare che rielaborazione stesso `document_id` non duplichi record e produca stesso risultato

  - [ ]* 11.2 Scrivere unit test per rollback su fallimento RDS
    - Testare marcatura `PERSISTENCE_ERROR` e rollback transazione
    - _Requirements: 7.6_

- [x] 12. Fault Tolerance e Resilienza
  - [x] 12.1 Configurare DLQ e at-least-once delivery per Event Router
    - Configurare SQS DLQ con visibilità delay 30 secondi e max 3 tentativi
    - Implementare reinserimento messaggio in coda su fallimento
    - Spostare messaggi non elaborabili in DLQ con metadata diagnostica (tentativi, ultimo errore, timestamp)
    - _Requirements: 12.1, 12.2, 12.3_

  - [x] 12.2 Implementare Circuit Breaker Resilience4j per SageMaker
    - Configurare Circuit Breaker (5 fallimenti → open 60s) per chiamate a SageMaker endpoint
    - Implementare fallback: restituire risultato parziale e segnalare degradazione al Pipeline_Orchestrator
    - _Requirements: 12.4_

  - [ ]* 12.3 Scrivere unit test per DLQ e retry logic
    - Verificare spostamento in DLQ dopo 3 tentativi falliti con metadata diagnostica corretta
    - _Requirements: 12.3_

  - [ ]* 12.4 Scrivere unit test per Circuit Breaker SageMaker
    - Verificare apertura circuito dopo 5 fallimenti e comportamento half-open
    - _Requirements: 12.4_

- [x] 13. Sicurezza e cifratura
  - [x] 13.1 Configurare KMS e cifratura storage
    - Configurare AWS KMS CMK per tenant per cifratura S3 at-rest (CDK)
    - Configurare cifratura RDS at-rest con KMS e TLS 1.2+ in transit (CDK)
    - Implementare re-cifratura documenti su rotazione chiave KMS senza interruzione servizio
    - _Requirements: 14.1, 14.2, 14.5_

  - [x] 13.2 Configurare IAM roles e CloudTrail
    - Creare IAM roles dedicati per ogni Lambda e ECS task con policy least privilege (CDK)
    - Configurare CloudTrail per audit trail con retention 90 giorni
    - _Requirements: 14.3, 14.4_

- [x] 14. Osservabilità e alerting
  - [x] 14.1 Implementare logging strutturato e X-Ray tracing
    - Implementare logging JSON strutturato su CloudWatch in ogni Lambda e microservizio (campi: `document_id`, `tenant_id`, `phase`, `duration_ms`, `status`)
    - Configurare AWS X-Ray tracing per ogni Lambda e Step Functions execution con span per fase e chiamate AWS
    - _Requirements: 11.1, 11.2_

  - [x] 14.2 Configurare metriche custom e CloudWatch Alarms
    - Pubblicare metriche custom CloudWatch: documenti/min, success rate, latenza per fase, costo/documento
    - Configurare CloudWatch Alarms: error rate >5% in 5min, latenza API >1.5s in 1min
    - _Requirements: 11.3, 11.4, 11.5_

- [x] 15. Scalabilità e performance
  - [x] 15.1 Configurare auto-scaling Lambda e ECS Fargate
    - Configurare Lambda concurrency limit a 1000 esecuzioni concorrenti (CDK)
    - Configurare ECS Fargate auto-scaling da 1 a 50 istanze su metriche CPU/memoria (CDK)
    - _Requirements: 13.1_

  - [x] 15.2 Implementare throttling e batch processing
    - Implementare throttling HTTP 429 con header `Retry-After` quando sistema >80% capacità
    - Implementare batch processing asincrono per documenti >10 pagine con notifica al completamento
    - _Requirements: 13.3, 13.4_

  - [ ]* 15.3 Scrivere unit test per throttling HTTP 429
    - Verificare risposta HTTP 429 con header `Retry-After` quando capacità >80%
    - _Requirements: 13.4_

- [x] 16. Frontend Angular
  - [x] 16.1 Implementare upload e dashboard
    - Creare componente Angular `DocumentUploadComponent` con drag-and-drop, selezione file e progress bar
    - Implementare polling/WebSocket per aggiornamento automatico stato documento nella dashboard
    - Implementare gestione errori: messaggio descrittivo e opzione retry per documenti `FAILED`
    - _Requirements: 9.1, 9.2, 9.5_

  - [x] 16.2 Implementare visualizzazione risultati e review
    - Creare componente `DocumentResultsComponent` con tabella KPI e overlay bounding box su PDF (PDF.js)
    - Creare `TenantDashboardComponent` con statistiche aggregate: documenti elaborati, distribuzione categorie, KPI per periodo
    - Implementare `ReviewComponent` per correzione manuale valori e approvazione documenti `NEEDS_REVIEW`
    - _Requirements: 9.3, 9.4, 9.6_

  - [ ]* 16.3 Scrivere unit test per componenti Angular
    - Testare upload con drag-and-drop e validazione formato lato client
    - Testare rendering KPI table e bounding box overlay
    - _Requirements: 9.1, 9.3_

- [x] 17. CI/CD Pipeline
  - [x] 17.1 Creare AWS CodePipeline
    - Creare pipeline con stages: source → build (CodeBuild) → unit test → integration test → deploy staging → manual approval → deploy production
    - Configurare rolling update ECS e Lambda versioned aliases per deploy senza downtime
    - _Requirements: 15.1, 15.2_

  - [x] 17.2 Implementare rollback automatico e ambienti separati
    - Implementare automatic rollback su health check failure entro 5 minuti
    - Configurare ambienti separati dev/staging/production con parameter store isolati
    - _Requirements: 15.3, 15.4_

- [x] 18. Checkpoint finale - Integrazione e wiring completo
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- I task contrassegnati con `*` sono opzionali e possono essere saltati per un MVP più rapido
- Ogni task referenzia i requisiti specifici per tracciabilità
- I property test usano jqwik (Java) per generare input arbitrari
- I Circuit Breaker usano Resilience4j
- L'isolamento multi-tenant è applicato a ogni layer: S3, DynamoDB, RDS, API
