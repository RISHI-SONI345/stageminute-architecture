# StageMinute — Modular Monolith Architecture & Systems Blueprint

<p align="center">
  <b>Production Architecture Showcase for <a href="https://stageminute.app">StageMinute (stageminute.app)</a></b><br/>
  An 11-module Spring Modulith engineered with Java 21 Virtual Threads, Hexagonal Architecture, Deterministic Domain Scoring, and ArchUnit Boundary Enforcement.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java_21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21" />
  <img src="https://img.shields.io/badge/Spring_Boot_3.4+-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white" alt="Spring Boot" />
  <img src="https://img.shields.io/badge/Spring_Modulith-6DB33F?style=for-the-badge&logo=spring&logoColor=white" alt="Spring Modulith" />
  <img src="https://img.shields.io/badge/PostgreSQL_17-316192?style=for-the-badge&logo=postgresql&logoColor=white" alt="PostgreSQL" />
  <img src="https://img.shields.io/badge/Resilience4j-FF6C37?style=for-the-badge&logo=resilience4j&logoColor=white" alt="Resilience4j" />
  <img src="https://img.shields.io/badge/ArchUnit-1.4+-00599C?style=for-the-badge&logo=junit5&logoColor=white" alt="ArchUnit" />
</p>

---

## 🏛️ Executive Summary

**StageMinute** is an AI-powered speech-coaching platform designed to deliver structured, high-accuracy acoustic and stylistic feedback on 60-second spoken takes under a **sub-12s p95 latency budget**. 

Rather than adopting a distributed microservices topology—which would introduce network latency, distributed serialization overhead, and eventual consistency complexity—StageMinute is architected as an **11-module Modular Monolith** using **Spring Modulith** and **Hexagonal Architecture**. 

This repository documents the production architectural patterns, concurrency mechanisms, data integrity guarantees, and automated boundary verification rules powering the system.

---

## 📐 System Context & Topology

```
                              [ React Native Mobile Client ]
                                             │
                                             ▼ (HTTPS / OpenAPI Contract)
┌───────────────────────────────── StageMinute Spring Boot Application ─────────────────────────────────┐
│                                                                                                       │
│   ┌────────────────────┐            ┌─────────────────────┐            ┌───────────────────────────┐  │
│   │  Platform Kernel   │            │   Practice Module   │            │     Analysis Module       │  │
│   │  - Idempotency     │◀───────────│   - Quota Ledger    │───────────▶│     - Pipeline Engine     │  │
│   │  - Spend Gate      │            │   - Rate Limiter    │            │     - Virtual Threads     │  │
│   │  - Event Log (TX)  │            │   - Session Reserve │            │     - Resilience4j Guards │  │
│   └────────────────────┘            └─────────────────────┘            └───────────────────────────┘  │
│             │                                                                        │                │
│             │                                                                        ▼                │
│             │                                                         ┌────────────────────────────┐  │
│             │                                                         │ Deterministic Scoring Core │  │
│             │                                                         │ (Pure Domain Logic - Java) │  │
│             │                                                         └────────────────────────────┘  │
│             ▼                                                                        │                │
│   ┌────────────────────┐            ┌─────────────────────┐                          │                │
│   │   Reports Module   │◀───────────│   Progress Module   │◀─────────────────────────┘                │
│   │   - Facts Ledger   │            │   - Streak Engine   │                                           │
│   │   - Dispute Engine │            │   - Outage Freezes  │                                           │
│   └────────────────────┘            └─────────────────────┘                                           │
│                                                                                                       │
│   * Modular isolation and acyclic graph invariants mathematically proven by ArchUnit & Modulith *     │
└───────────────────────────────────────────────────┬───────────────────────────────────────────────────┘
                                                    │
                 ┌──────────────────────────────────┴──────────────────────────────────┐
                 ▼                                                                     ▼
       [ PostgreSQL 17 Cluster ]                                            [ External AI Engines ]
       - Transactional Outbox                                               - Deepgram Nova-3 (STT)
       - Atomic Quota Rows                                                  - Gemini Flash (Structured Labels)
       - Durable Webhook Inboxes
```

---

## 🧩 The 11 Bounded Modules

Every module strictly abides by the **Encapsulated Root Contract**:
> *The root package of each module defines its public API (services, events, records). All implementations, entities, adapters, and repositories reside in `.internal` or `.web` and are invisible to other modules.*

| Module | Core Responsibility | Public Interface Entry Points | Boundary Invariant |
| :--- | :--- | :--- | :--- |
| **`platform`** | Shared kernel: idempotency keys, system clock, spend gate, and transactional event publication log. | `SpendGate`, `SpendDecision`, `EventPublicationLog`, `ProblemAdvice` | Zero dependencies on any business module. |
| **`practice`** | Practice session reservations, attempt rules (2+1 model), and quota ledger enforcement. | `Practice`, `Reservation`, `Quota`, `AttemptRule` | Must never trust client clocks; quota updates require atomic database locks. |
| **`analysis`** | Asynchronous orchestration of STT transcription, LLM structured acoustic labeling, and scoring. | `Analyzer`, `Pipeline`, `VendorGuards`, `SpendMeter` | LLMs are never permitted to generate numerical scores; score path never reads subscription tier. |
| **`reports`** | Immutable report generation, dispute handling, and historical speech pattern analysis. | `ReportV2`, `FactsRecord`, `Reports`, `Disputes` | Must never store raw audio or PII transcripts in primary fact rows. |
| **`progress`** | Streak tracking, practice milestones, and automated outage freeze compensations. | `Progress`, `StreakView`, `OutageWindows` | Idempotent progression calculations; survives client clock drifting. |
| **`account`** | Account lifecycle, user consent tracking, GDPR deletion, and timezone reconciliation. | `AccountService`, `ProfileService`, `ConsentService` | Asynchronous propagation of deletion events across downstream modules. |
| **`identity`** | Auth verification via Clerk JWT validation and secure webhook ingestion. | `ClerkTokenValidators`, `ClerkWebhookHandler` | Zero knowledge of user profiles or billing tiers. |
| **`topics`** | Read-only topic catalog, validated via JSON Schema at bootstrap. | `TopicCatalog`, `Level`, `Style` | Completely stateless and immutable at runtime. |
| **`me`** | Aggregating composition layer building `GET /me` responses. | `MeController` | Pure consumer; no other module may depend on `me`. |
| **`money`** | Entitlement verification and resilient billing webhook processing. | `RevenueCatDeletionListener` | Webhooks must be logged to a durable inbox prior to transaction execution. |
| **`admin`** | Operational audit logs and diagnostic health checks. | `AuditLogger` | Read-only reporting and authorized operator overrides. |

---

## ⚡ Core Engineering Highlights

### 1. High-Throughput Async AI Pipeline (Java 21 Virtual Threads)
When evaluating audio takes, the platform issues concurrent I/O calls to Deepgram Nova-3 (streaming transcription) and Gemini Flash (acoustic and stylistic evaluation). 
* **Virtual Threads:** We leverage `Executors.newVirtualThreadPerTaskExecutor()` to dispatch network I/O without monopolizing OS platform worker threads.
* **Resilience4j Bulkheads:** Each vendor client is isolated behind a dedicated concurrency bulkhead and circuit breaker, preventing an upstream vendor outage from cascading into system-wide thread starvation.
* **Turnaround Latency:** Guaranteed **p95 latency < 12 seconds** from audio upload to completed report.

### 2. Deterministic Pure Domain Scoring Core
A core design tenet of StageMinute is: **LLMs must never generate numerical scores or evaluation grades.**
* Large Language Models suffer from stochastic scoring drift, prompt sensitivity, and score inflation.
* **Our Solution:** The LLM is constrained strictly to returning structured qualitative labels (`strong | weak | missing`, identified filler instances, and sentence segmentation).
* The actual numerical evaluation is handled by a **Pure Domain Mathematical Model** in Java:
  ```java
  // Enforced by ArchUnit: Zero external dependencies in scoring engine
  @ArchTest
  static final ArchRule scoreIsPure = classes()
      .that().resideInAPackage("app.stageminute.analysis.score..")
      .should().onlyDependOnClassesThat()
      .resideInAnyPackage("java..", "app.stageminute.analysis.score..")
      .because("the score is a pure mathematical function of facts and labels");
  ```
* **Testing:** Validated with **99% test branch coverage across 42 audio fixtures**, ensuring deterministic, reproducible scores.

### 3. Concurrency-Safe Quota & Cost Controls
To protect against race conditions, duplicate operations, and quota exploitation:
* **Atomic SQL Updates:** Session reservations execute conditional SQL updates directly on the database engine:
  ```sql
  UPDATE quotas 
  SET used = used + 1 
  WHERE user_id = :userId AND used < allowance;
  ```
* **Pre-allocated Spend Meter:** The `SpendMeter` estimates and pre-allocates maximum API token spend against an in-memory sliding window before granting upstream vendor dispatch, preventing budget exhaustion during high-concurrency spikes.
* **Idempotency Keys:** Every mutating endpoint requires a unique client-generated idempotency key, stored and checked with unique database constraints.

### 4. Transactional Outbox & Durable Webhook Inbox
* **Outbox Pattern:** Cross-module side effects (e.g., account deletion, subscription entitlement sync) write domain events directly into the `event_publication` table within the active database transaction.
* **Resilient Retry Dispatcher:** A background worker monitors uncommitted or failed events, providing at-least-once delivery with exponential backoff and dead-letter alerting.

---

## 🧪 Architecture Verification via ArchUnit & Modulith

Architecture documentation often diverges from reality over time. In StageMinute, the architectural rules are **compiled unit tests that fail the build if violated**:

```java
class ModularityTests {
    static final ApplicationModules MODULES = ApplicationModules.of(StageMinuteApplication.class);

    @Test
    void modulesRespectTheirWalls() {
        // Enforces package encapsulation and acyclic dependency graphs
        MODULES.verify();
    }

    @Test
    void meIsAComposerOfFiveModulesAndNothingUsesIt() {
        // Enforces that 'me' is purely a composer and never a dependency
        ApplicationModule me = MODULES.getModuleByName("me").orElseThrow();
        boolean someoneUsesMe = MODULES.stream()
                .filter(m -> !m.equals(me))
                .anyMatch(m -> m.getDirectDependencies(MODULES).contains(me));
        assertThat(someoneUsesMe).isFalse();
    }
}
```

---

## 🛠️ Build & Run

### Prerequisites
* JDK 21+
* Gradle (or bundled `gradlew`)

```bash
# Clone the architecture showcase
git clone https://github.com/RISHI-SONI345/stageminute-architecture.git
cd stageminute-architecture

# Run module boundary and architecture tests
./gradlew test
```

---

## 📄 License & Attribution

Architecture design, domain models, and verification suites authored by **Rishi Soni**.  
*For live product demonstrations, visit [stageminute.app](https://stageminute.app).*
