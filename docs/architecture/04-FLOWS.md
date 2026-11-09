# Key flows and life cycles

Sequence diagrams for the six flows that carry money, privacy or score rules, and the two state machines. Each step cites the requirement it serves.

## 1. Sign in (02a F-010, F-011; Clerk, AD-53)

```mermaid
sequenceDiagram
  participant App
  participant Clerk
  participant IdP as Apple / Google
  participant ID as identity (server)
  participant ME as me (server)
  participant AC as account (server)
  App->>IdP: native sign-in (useSignInWithApple / useSignInWithGoogle)
  IdP-->>App: identity token
  App->>Clerk: hand over the token (Clerk SDK)
  Clerk-->>App: Clerk session (token cache in secure storage)
  App->>ID: GET /me  Authorization: Bearer (Clerk session token)
  ID->>ID: verify with Clerk JWKS (exp, nbf, iss, azp)
  ID->>ME: authenticated user id (Clerk sub)
  ME->>AC: find or create user by clerk_user_id (velocity limits on create)
  ME-->>App: /me snapshot (needs_consent → consent screen)
```

Clerk refreshes session tokens on the phone; an ended or revoked session → `401 session_expired` → the session-expired sheet (never during a take).

## 2. Start a session (03 F-048 to F-051; 06 F-086)

```mermaid
sequenceDiagram
  participant App
  participant ME as me
  participant P as practice
  participant M as money
  participant DB as Postgres
  App->>App: tap Start speaking (online only, D-37)
  App->>ME: GET /me (quota state)
  alt quota_zero
    App->>App: hard gate (three exits), topic kept
  else pro_cap or throttled
    App->>App: calm board, no ad, no Pro offer
  else ok
    App->>App: mic primer, Think, Get ready, recorder opens at "1"
    App->>P: POST /sessions {takeKey, topicId} (Idempotency-Key = takeKey)
    P->>M: plan(user) (free / pro)
    P->>DB: INSERT practice_sessions ... ON CONFLICT (user_id, take_key) DO NOTHING (one transaction)
    Note over P,DB: conflict = same take sent again, return the stored session, no second count
    P->>DB: UPDATE daily_counters SET sessions_used+1 WHERE < allowance RETURNING
    P->>DB: INSERT quota_events (reserve, -1)
    P-->>App: 201 {sessionId, attempt: 1}
  end
```

The reservation happens only after the recorder opened (D-66), so a failed start never uses a session.

## 3. Analyze a take (03 F-026 to F-037, F-091; 05 F-081)

```mermaid
sequenceDiagram
  participant App
  participant A as analysis
  participant P as practice
  participant DG as Deepgram
  participant GM as Gemini
  participant R as reports
  App->>A: POST /analyze multipart {audio, sessionId, takeKey, durationMs} (Idempotency-Key)
  A->>A: consent? size ≤ 8 MB, format? rate 6/min? throttle level?
  A->>A: idempotency lookup → stored report? return it
  A->>P: beginAttempt(sessionId, takeKey) (attempt rule, analyses cap)
  A->>A: audio → temp file (deleted in finally)
  A->>DG: transcribe (nova-3, mip_opt_out=true)
  DG-->>A: words, times, confidence, sentences
  A->>A: Metrics v2 → NoScoreGate
  alt no score
    A->>P: complete(no_score) → release (or count after 5/day, D-86)
    A-->>App: 200 report{status: short_take | no_speech | not_english | too_quiet}
  else scored
    A->>GM: ONE labels-only call (enum schema, topic + transcript)
    GM-->>A: labels + words (verdict, Try line, rewrite)
    A->>A: LabelValidator (no numbers) → ScoreBuilder → FixLibrary → FocusCheck
    A->>R: save(report + facts) ─┐ one transaction
    A->>P: complete(scored) → commit ┘ + idempotency entry + SessionCommitted event
    A-->>App: 200 {report v2, speech_data}
  end
  Note over A: vendor failure → 502 analysis_unavailable, session given back (no cap when server-confirmed)
```

The phone saves `speech_data` next to the take (7 days) and never sends it back.

## 4. Rewarded ad credit (04 F-063, F-064, F-114)

```mermaid
sequenceDiagram
  participant App
  participant M as money
  participant Ad as AdMob
  App->>M: POST /ads/rewarded/prepare
  M-->>App: signed custom_data (user, nonce, exp)
  App->>Ad: show rewarded ad (user chose it)
  Ad->>M: GET /webhooks/admob-ssv?...&signature&key_id
  M->>M: reverse-DNS is Google, verify ECDSA over query minus signature,key_id (keys cached ≤ 24 h), timestamp ±5 min, custom_data signature
  M->>M: INSERT ad_reward_grants(transaction_id) ON CONFLICT DO NOTHING
  M->>M: UPDATE daily_counters SET ad_credits+1 WHERE ad_credits < cap RETURNING (cap from settings, same tx)
  M-->>Ad: 200 (always, even when not credited)
  App->>M: poll GET /me every 2 s, up to 5 times
  M-->>App: ad_rewards_today = 1 → "+1 · 1 more session added"
```

## 5. Purchase and entitlement (04 F-071 to F-073; 03 F-092)

```mermaid
sequenceDiagram
  participant App
  participant RC as RevenueCat SDK / server
  participant M as money
  App->>RC: purchase(annual with trial)
  RC-->>App: result (map to one board, "no charge" only where RevenueCat says so)
  App->>M: POST /me/entitlement/sync
  M->>RC: GET subscriber (server key)
  M->>M: upsert entitlements if newer than last_event_at
  M-->>App: tier = pro, trial_ends_at → Pro welcome
  RC->>M: POST /webhooks/revenuecat (auth header) INITIAL_PURCHASE ...
  M->>M: INSERT webhook_events(source, id) ON CONFLICT → duplicate? ack
  M->>M: apply only if event_timestamp_ms > last_event_at, then publish EntitlementChanged
  M-->>RC: 200
  Note over M: daily reconcile job pulls subscribers changed in the last 48 h
```

## 6. Delete account (05 F-082)

```mermaid
sequenceDiagram
  participant App
  participant AC as account
  participant Out as event publication (outbox)
  participant V as Clerk / RevenueCat / Superwall / PostHog
  App->>AC: DELETE /me (online only)
  AC->>Out: publish AccountDeletionRequested(userId, clerk user id, rc id) ┐
  AC->>AC: INSERT deleted_users, then DELETE FROM users (cascade)               ┘ one transaction
  AC-->>App: 204
  Out->>V: Clerk deleteUser, RevenueCat delete, Superwall delete, PostHog delete (retry until done, alert if stuck > 6 h)
  App->>App: wipe tokens, takes, transcripts, caches, notifications, reset SDK identities, show 32b
```

Built in #18.1 (AD-149). The listeners are one per vendor, so each retries alone (every 5 minutes; an alert after 6 hours). **Apple:** Clerk's own guide says deleting the Clerk user does not reset Apple's side, so Clerk does not revoke the Apple grant; no revoke is built and C-78 asks the founder. We keep no Apple token copy. **Superwall** has no delete call (C-79): its listener records a manual step.

Webhooks that arrive later for this user are acknowledged and ignored (`ignored_unknown_user`), including Clerk's own `user.deleted` echo.

## 7. Session life cycle (server)

```mermaid
stateDiagram-v2
  [*] --> reserved: POST /sessions (after recorder opens)
  reserved --> committed: first scored result delivered
  committed --> committed: further attempts on the same day (2 attempts, a third only after Better or Not yet)
  reserved --> released: server failure, timeout, discard, cancel, abandon, no-score (first 5/day)
  reserved --> released: no result 48 h after reservation (job)
  reserved --> committed: attempts end on counted no-score takes (6th+ a day, D-86)
  released --> [*]
  committed --> [*]: end of account day
```

## 8. Take life cycle (app, AD-28)

```mermaid
stateDiagram-v2
  [*] --> recorded: recorder closes file (AAC) + sidecar JSON
  recorded --> uploading: Use this take (online)
  recorded --> deleted: Re-record accepted / Delete take
  uploading --> waiting: connection lost ("Waiting to send")
  waiting --> uploading: reconnect, app in foreground
  uploading --> analyzing: upload complete
  analyzing --> done: 200 report (+ speech_data saved in sidecar)
  analyzing --> failed: 502 / timeout ("Not sent", Try again, session given back)
  failed --> uploading: Try again (same idempotency key)
  done --> purged: 7 days old (audio + transcript removed, report stays on server)
  recorded --> warned: unsent, 2 days before expiry
  waiting --> warned: unsent, 2 days before expiry
  failed --> warned: unsent, 2 days before expiry
  warned --> uploading: Use this take or Upload first (never purged silently while unsent)
  warned --> deleted: user deletes the take
```
