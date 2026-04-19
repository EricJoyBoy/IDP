# Requirements Document

## Introduction

Piattaforma di Intelligent Document Processing (IDP) production-ready su AWS per l'elaborazione automatizzata di documenti finanziari. Il sistema acquisisce documenti tramite API REST e frontend Angular, li elabora attraverso una pipeline event-driven orchestrata con AWS Step Functions, estrae dati strutturati con Amazon Textract, applica analisi semantica e modelli ML custom per classificazione e KPI finanziari, e restituisce risultati normalizzati tramite API. La piattaforma è progettata come SaaS multi-tenant, scalabile orizzontalmente, con fault tolerance e cost optimization serverless-first.

## Glossary

- **IDP_Platform**: Il sistema complessivo di Intelligent Document Processing
- **Ingestion_API**: Il componente REST API (Spring Boot + API Gateway) responsabile dell'accettazione dei documenti in upload
- **Document_Store**: Amazon S3 con versioning abilitato, storage primario dei documenti
- **Event_Router**: AWS Lambda trigger su eventi S3, responsabile dell'avvio della pipeline
- **Pipeline_Orchestrator**: AWS Step Functions, responsabile dell'orchestrazione dell'intero workflow di elaborazione
- **Textract_Adapter**: AWS Lambda che invoca Amazon Textract e normalizza l'output in DTO Java
- **NLP_Processor**: Componente che integra Amazon Comprehend per analisi semantica
- **ML_Classifier**: Modello custom su Amazon SageMaker per classificazione documenti e estrazione KPI finanziari
- **DTO_Normalizer**: Microservizio Spring Boot che trasforma l'output grezzo di Textract in DTO strutturati e validati via JSON Schema
- **Metadata_Store**: Amazon DynamoDB, storage a bassa latenza per metadata e risultati di elaborazione
- **Relational_Store**: Amazon RDS, storage relazionale per dati con consistenza transazionale
- **Data_Lake**: Amazon S3 dedicato ad analytics e storage storico
- **API_Gateway**: Amazon API Gateway, entry point per tutte le richieste esterne
- **Auth_Service**: Amazon Cognito, responsabile di autenticazione e autorizzazione
- **Frontend**: Applicazione Angular per upload documenti, dashboard e visualizzazione risultati
- **DLQ**: Dead Letter Queue, coda per messaggi non elaborabili
- **Tenant**: Organizzazione cliente nel contesto SaaS multi-tenant
- **KPI**: Key Performance Indicator finanziario estratto dai documenti
- **Circuit_Breaker**: Pattern di resilienza per isolare failure di servizi esterni

---

## Requirements

### Requirement 1: Ingestion documenti via API REST

**User Story:** As a utente autenticato, I want to caricare documenti finanziari tramite API REST, so that il sistema possa avviare automaticamente l'elaborazione.

#### Acceptance Criteria

1. WHEN un utente autenticato invia una richiesta HTTP POST con un documento allegato all'endpoint di upload, THE Ingestion_API SHALL accettare il documento e restituire un `document_id` univoco entro 2 secondi.
2. WHEN il documento viene ricevuto dall'Ingestion_API, THE Document_Store SHALL salvare il documento su Amazon S3 con versioning abilitato e metadata di tenant, timestamp e formato.
3. IF il documento supera la dimensione massima consentita di 50 MB, THEN THE Ingestion_API SHALL restituire un errore HTTP 413 con un messaggio descrittivo.
4. IF il formato del documento non è tra i tipi supportati (PDF, PNG, JPEG, TIFF), THEN THE Ingestion_API SHALL restituire un errore HTTP 415 con l'elenco dei formati accettati.
5. THE Ingestion_API SHALL supportare upload multi-part per documenti di dimensione superiore a 5 MB.
6. WHILE un upload multi-part è in corso, THE Ingestion_API SHALL mantenere lo stato della sessione di upload per un massimo di 24 ore.

---

### Requirement 2: Trigger event-driven su upload

**User Story:** As a sistema, I want che ogni nuovo documento su S3 avvii automaticamente la pipeline di elaborazione, so that il processing sia asincrono e disaccoppiato dall'ingestion.

#### Acceptance Criteria

1. WHEN un nuovo oggetto viene creato nel Document_Store, THE Event_Router SHALL avviare una nuova esecuzione del Pipeline_Orchestrator entro 5 secondi dall'evento S3.
2. THE Event_Router SHALL trasmettere al Pipeline_Orchestrator il `document_id`, il `tenant_id`, il percorso S3 e il timestamp di upload come payload di input.
3. IF il Pipeline_Orchestrator non è raggiungibile, THEN THE Event_Router SHALL inviare il messaggio alla DLQ e registrare l'errore su Amazon CloudWatch.
4. THE Event_Router SHALL garantire l'idempotenza: un documento con lo stesso `document_id` non SHALL avviare più di una esecuzione attiva del Pipeline_Orchestrator.

---

### Requirement 3: Orchestrazione pipeline con Step Functions

**User Story:** As a sistema, I want che il workflow di elaborazione sia orchestrato in modo affidabile e tracciabile, so that ogni fase sia monitorabile e i fallimenti siano gestiti con retry automatici.

#### Acceptance Criteria

1. THE Pipeline_Orchestrator SHALL eseguire le fasi di elaborazione nel seguente ordine: estrazione testo (Textract), normalizzazione DTO, analisi semantica (NLP), classificazione ML, persistenza risultati.
2. WHEN una fase del workflow fallisce, THE Pipeline_Orchestrator SHALL eseguire un retry con backoff esponenziale per un massimo di 3 tentativi prima di marcare il documento come `FAILED`.
3. WHEN il documento viene marcato come `FAILED` dopo i retry esauriti, THE Pipeline_Orchestrator SHALL inviare una notifica alla DLQ e aggiornare lo stato nel Metadata_Store.
4. THE Pipeline_Orchestrator SHALL implementare il Saga pattern: in caso di fallimento irreversibile, SHALL eseguire le compensating transactions per annullare le fasi già completate.
5. WHEN l'elaborazione di un documento è completata con successo, THE Pipeline_Orchestrator SHALL aggiornare lo stato del documento a `COMPLETED` nel Metadata_Store entro 1 secondo dal completamento.
6. THE Pipeline_Orchestrator SHALL supportare l'esecuzione parallela di più documenti senza interferenze tra esecuzioni appartenenti a Tenant diversi.

---

### Requirement 4: Estrazione dati con Amazon Textract

**User Story:** As a sistema, I want estrarre testo, form e tabelle dai documenti finanziari, so that i dati strutturati siano disponibili per le fasi successive della pipeline.

#### Acceptance Criteria

1. WHEN un documento PDF o immagine è disponibile nel Document_Store, THE Textract_Adapter SHALL invocare Amazon Textract per l'estrazione di testo, form (key-value pairs) e tabelle.
2. THE Textract_Adapter SHALL normalizzare l'output grezzo di Amazon Textract in DTO Java strutturati, validati tramite JSON Schema prima della trasmissione alla fase successiva.
3. IF Amazon Textract restituisce un errore o un risultato parziale, THEN THE Textract_Adapter SHALL registrare l'errore su CloudWatch e segnalare il fallimento al Pipeline_Orchestrator.
4. THE DTO_Normalizer SHALL produrre DTO che includono: testo estratto, lista di key-value pairs con confidence score, lista di tabelle con righe e colonne, e bounding box per ogni elemento.
5. FOR ALL documenti validi, il processo di parsing dell'output Textract e la sua serializzazione in DTO SHALL produrre un oggetto equivalente se deserializzato e ri-serializzato (round-trip property).
6. WHERE il documento contiene tabelle multi-pagina, THE Textract_Adapter SHALL ricombinare le righe di tabella distribuite su più pagine in un'unica struttura coerente.

---

### Requirement 5: Analisi semantica con Amazon Comprehend

**User Story:** As a sistema, I want analizzare semanticamente il testo estratto, so that entità, sentiment e relazioni rilevanti per il dominio finanziario siano identificate.

#### Acceptance Criteria

1. WHEN il testo estratto da un documento è disponibile, THE NLP_Processor SHALL invocare Amazon Comprehend per il riconoscimento di entità (organizzazioni, date, importi monetari, percentuali).
2. THE NLP_Processor SHALL arricchire il DTO con le entità riconosciute, includendo tipo, valore, posizione nel testo e confidence score.
3. IF Amazon Comprehend non è disponibile, THEN THE NLP_Processor SHALL attivare il Circuit_Breaker, restituire un risultato parziale senza entità e segnalare la degradazione al Pipeline_Orchestrator.
4. WHILE il Circuit_Breaker è aperto per Amazon Comprehend, THE NLP_Processor SHALL continuare l'elaborazione delle fasi successive senza bloccare la pipeline.

---

### Requirement 6: Classificazione documenti e estrazione KPI con SageMaker

**User Story:** As a sistema, I want classificare automaticamente i documenti finanziari ed estrarre KPI rilevanti, so that i risultati siano immediatamente utilizzabili per analisi business.

#### Acceptance Criteria

1. WHEN il DTO normalizzato e arricchito è disponibile, THE ML_Classifier SHALL invocare il modello SageMaker per classificare il documento in una delle categorie supportate (bilancio, conto economico, rendiconto finanziario, contratto, fattura, altro).
2. THE ML_Classifier SHALL estrarre i KPI finanziari definiti per la categoria classificata (es. ricavi, EBITDA, utile netto, totale attivo) con il relativo valore numerico, unità di misura e confidence score.
3. IF il confidence score della classificazione è inferiore a 0.7, THEN THE ML_Classifier SHALL marcare il documento come `NEEDS_REVIEW` e includere le top-3 categorie candidate con i rispettivi score.
4. THE ML_Classifier SHALL restituire i risultati entro 10 secondi per documenti fino a 20 pagine.
5. WHERE un modello SageMaker custom aggiornato è disponibile, THE ML_Classifier SHALL utilizzare la versione del modello specificata nella configurazione del Tenant senza richiedere il riavvio del servizio.

---

### Requirement 7: Persistenza metadata e risultati

**User Story:** As a sistema, I want persistere metadata e risultati di elaborazione in modo affidabile, so that siano accessibili con bassa latenza per le API e per analytics.

#### Acceptance Criteria

1. WHEN l'elaborazione di un documento è completata, THE Pipeline_Orchestrator SHALL persistere i risultati strutturati nel Metadata_Store (DynamoDB) con chiave primaria `tenant_id#document_id`.
2. THE Metadata_Store SHALL garantire la lettura dei risultati con latenza inferiore a 10 ms al 99° percentile per chiavi note.
3. WHEN i risultati vengono scritti nel Metadata_Store, THE Pipeline_Orchestrator SHALL replicare i dati relazionali (KPI, classificazioni, relazioni tra documenti) nel Relational_Store (RDS) in modo transazionale.
4. THE Pipeline_Orchestrator SHALL archiviare il documento originale, il DTO grezzo e i risultati finali nel Data_Lake su S3 con partizione per `tenant_id/year/month/day`.
5. THE Metadata_Store SHALL supportare query per `tenant_id`, `status`, `document_type` e `date_range` con latenza inferiore a 50 ms.
6. IF una scrittura nel Relational_Store fallisce, THEN THE Pipeline_Orchestrator SHALL eseguire un rollback della transazione e marcare il documento come `PERSISTENCE_ERROR` nel Metadata_Store.

---

### Requirement 8: API di consultazione risultati

**User Story:** As a utente autenticato, I want consultare lo stato e i risultati dell'elaborazione tramite API REST, so that possa integrare i dati estratti nei propri sistemi.

#### Acceptance Criteria

1. WHEN un utente autenticato invia una richiesta GET con un `document_id` valido, THE API_Gateway SHALL restituire lo stato e i risultati dell'elaborazione entro 2 secondi.
2. THE Ingestion_API SHALL esporre endpoint per: recupero stato documento, recupero risultati completi, lista documenti per tenant con paginazione, e ricerca per KPI e date range.
3. IF un utente tenta di accedere a un documento appartenente a un Tenant diverso dal proprio, THEN THE API_Gateway SHALL restituire HTTP 403 senza rivelare l'esistenza del documento.
4. THE Ingestion_API SHALL implementare caching dei risultati frequenti con TTL configurabile per ridurre il carico sul Metadata_Store.
5. WHEN i risultati di un documento sono già in cache, THE Ingestion_API SHALL restituire la risposta dalla cache con header `X-Cache: HIT` e latenza inferiore a 100 ms.

---

### Requirement 9: Frontend Angular

**User Story:** As a utente business, I want un'interfaccia web per caricare documenti e visualizzare i risultati, so that possa operare sulla piattaforma senza competenze tecniche.

#### Acceptance Criteria

1. THE Frontend SHALL consentire l'upload di documenti tramite drag-and-drop e selezione file, con indicatore di progresso in tempo reale.
2. WHEN l'elaborazione di un documento è completata, THE Frontend SHALL aggiornare automaticamente la dashboard senza richiedere un refresh manuale della pagina.
3. THE Frontend SHALL visualizzare i KPI estratti in formato tabellare con evidenziazione delle celle corrispondenti nel documento PDF originale (bounding box overlay).
4. THE Frontend SHALL mostrare una dashboard aggregata per Tenant con statistiche su: numero documenti elaborati, distribuzione per categoria, KPI aggregati per periodo.
5. IF l'elaborazione di un documento fallisce, THE Frontend SHALL mostrare un messaggio di errore descrittivo con l'opzione di ritentare l'upload.
6. WHERE la funzionalità di revisione manuale è abilitata per il Tenant, THE Frontend SHALL consentire agli utenti di correggere i valori estratti e approvare i documenti in stato `NEEDS_REVIEW`.

---

### Requirement 10: Autenticazione e autorizzazione

**User Story:** As a amministratore, I want che l'accesso alla piattaforma sia protetto e isolato per tenant, so that i dati di ogni cliente siano accessibili solo agli utenti autorizzati.

#### Acceptance Criteria

1. THE Auth_Service SHALL autenticare gli utenti tramite Amazon Cognito con supporto per username/password e OAuth 2.0 / OIDC.
2. WHEN un utente si autentica con successo, THE Auth_Service SHALL emettere un JWT con claims che includono `tenant_id`, `user_id` e `roles`.
3. THE API_Gateway SHALL validare il JWT su ogni richiesta e rifiutare le richieste con token assente, scaduto o non valido con HTTP 401.
4. THE IDP_Platform SHALL applicare isolamento completo dei dati tra Tenant: nessuna query, nessun accesso a file S3 e nessuna lettura da DynamoDB SHALL attraversare i confini del Tenant.
5. IF un utente tenta un'operazione non consentita dal proprio ruolo, THEN THE API_Gateway SHALL restituire HTTP 403 con un messaggio descrittivo del permesso mancante.

---

### Requirement 11: Osservabilità e alerting

**User Story:** As a operations team, I want visibilità completa sullo stato della pipeline e alerting proattivo, so that i problemi siano identificati e risolti prima di impattare gli utenti.

#### Acceptance Criteria

1. THE IDP_Platform SHALL registrare su Amazon CloudWatch log strutturati (JSON) per ogni fase della pipeline, includendo `document_id`, `tenant_id`, `phase`, `duration_ms` e `status`.
2. THE IDP_Platform SHALL emettere trace distribuiti tramite AWS X-Ray per ogni esecuzione della pipeline, con span per ogni fase e per ogni chiamata a servizi AWS.
3. WHEN il tasso di errore della pipeline supera il 5% nell'arco di 5 minuti, THE IDP_Platform SHALL inviare un alert tramite Amazon CloudWatch Alarms.
4. WHEN la latenza media delle API supera 1.5 secondi nell'arco di 1 minuto, THE IDP_Platform SHALL inviare un alert tramite Amazon CloudWatch Alarms.
5. THE IDP_Platform SHALL esporre metriche custom su CloudWatch per: documenti elaborati per minuto, tasso di successo/fallimento, latenza per fase, e costo stimato per documento.

---

### Requirement 12: Fault tolerance e resilienza

**User Story:** As a sistema, I want gestire i fallimenti in modo resiliente, so that un errore in un componente non causi la perdita di documenti o dati.

#### Acceptance Criteria

1. THE Pipeline_Orchestrator SHALL implementare retry con backoff esponenziale (base 2s, max 60s) per tutte le chiamate a servizi AWS esterni.
2. THE Event_Router SHALL garantire la consegna at-least-once dei messaggi: in caso di fallimento, il messaggio SHALL essere reinserito nella coda con visibilità delay di 30 secondi.
3. IF un messaggio non viene elaborato con successo dopo 3 tentativi, THEN THE Event_Router SHALL spostarlo nella DLQ con metadata di diagnostica (numero tentativi, ultimo errore, timestamp).
4. THE IDP_Platform SHALL implementare il Circuit_Breaker pattern per le chiamate a Amazon Comprehend e Amazon SageMaker: dopo 5 fallimenti consecutivi, il circuito SHALL aprirsi per 60 secondi.
5. THE Pipeline_Orchestrator SHALL garantire l'idempotenza: la rielaborazione di un documento con lo stesso `document_id` SHALL produrre lo stesso risultato finale senza duplicare record nel Metadata_Store.

---

### Requirement 13: Scalabilità e performance

**User Story:** As a sistema, I want scalare automaticamente in base al carico, so that le performance siano mantenute anche durante picchi di utilizzo.

#### Acceptance Criteria

1. THE IDP_Platform SHALL scalare orizzontalmente in modo automatico: le Lambda SHALL scalare fino a 1000 esecuzioni concorrenti, i container ECS Fargate SHALL scalare da 1 a 50 istanze in base al carico CPU/memoria.
2. THE Ingestion_API SHALL rispondere alle richieste di upload entro 2 secondi al 95° percentile sotto un carico di 100 richieste concorrenti.
3. THE IDP_Platform SHALL supportare batch processing per documenti pesanti: documenti superiori a 10 pagine SHALL essere elaborati in modalità asincrona con notifica al completamento.
4. WHILE il sistema è sotto carico elevato (>80% capacità), THE IDP_Platform SHALL applicare throttling sulle nuove richieste di ingestion restituendo HTTP 429 con header `Retry-After`.
5. THE IDP_Platform SHALL ottimizzare i costi con un approccio serverless-first: le risorse SHALL essere allocate on-demand e deallocate quando non in uso.

---

### Requirement 14: Sicurezza e cifratura

**User Story:** As a compliance officer, I want che tutti i dati siano cifrati e gli accessi tracciati, so that la piattaforma rispetti i requisiti di sicurezza e conformità.

#### Acceptance Criteria

1. THE Document_Store SHALL cifrare tutti i documenti at-rest con AWS KMS usando chiavi gestite per Tenant.
2. THE Relational_Store SHALL cifrare i dati at-rest con AWS KMS e le connessioni in transit con TLS 1.2 o superiore.
3. THE IDP_Platform SHALL applicare il principio del least privilege: ogni componente Lambda e ECS SHALL operare con un IAM role dedicato con i soli permessi necessari alla propria funzione.
4. THE IDP_Platform SHALL registrare tutti gli accessi alle API e alle risorse AWS tramite AWS CloudTrail per un periodo di retention di almeno 90 giorni.
5. IF una chiave KMS viene ruotata, THEN THE Document_Store SHALL re-cifrare i documenti esistenti con la nuova chiave senza interruzione del servizio.

---

### Requirement 15: CI/CD e deploy

**User Story:** As a development team, I want una pipeline CI/CD automatizzata, so that le modifiche al codice siano testate e deployate in modo affidabile e ripetibile.

#### Acceptance Criteria

1. THE IDP_Platform SHALL disporre di una pipeline CI/CD che esegua automaticamente build, test unitari, test di integrazione e deploy ad ogni push sul branch principale.
2. WHEN tutti i test passano con successo, THE IDP_Platform SHALL deployare automaticamente le Lambda aggiornate e aggiornare i task definition ECS Fargate senza downtime (rolling update).
3. IF un deploy introduce una regressione rilevata dai health check, THEN THE IDP_Platform SHALL eseguire un rollback automatico alla versione precedente entro 5 minuti.
4. THE IDP_Platform SHALL supportare ambienti separati (development, staging, production) con configurazioni isolate e promozione manuale da staging a production.
