# QExt2 Ride Ask / QBot Advice Field — Predesign

Status: PREDESIGN (no runtime changes)
Phase target: documentation + mock tests (Phase 0)

## 1. Product Goal

Osobne pole danych QExt2 (`qext2-advice`) z przyciskiem ODPYTAJ. Użytkownik świadomie wysyła snapshot swojej jazdy do QBot. QBot odsyła krótką radę (1–2 linie), która jest wyświetlana w dedykowanym polu.

- Nie primary field.
- Nie ACTIVE MSG (to nie jest alert dynamiczny).
- Nie automatyczne — tylko ręczny trigger.
- Clear idle/sending/response/error states.

## 2. UI Concept

```
┌─────────────────────┐
│  ODPYTAJ            │  <- idle
├─────────────────────┤
│  PYTAM...           │  <- sending
├─────────────────────┤
│  Zwolnij tempo,     │  <- response (2 lines max)
│  podjazd za 3 km    │
├─────────────────────┤
│  14:32 zakt.        │  <- response (small, optional)
├─────────────────────┤
│  BRAK ODPOWIEDZI    │  <- error/offline/timeout
└─────────────────────┘
```

States:
- `IDLE` — empty or last cached advice with timestamp. Show "ODPYTAJ" tap target.
- `SENDING` — "PYTAM..." with subtle progress indicator. Tap disabled (debounce 15s).
- `RESPONSE` — short advice text (max 80 chars), severity color, category small label, timestamp
- `ERROR` — "BRAK ODPOWIEDZI" with reason in logs (not UI)

Layout: `field_advice_2x1.xml` — simple 2-row widget, fits in 2x1 grid cell.

## 3. Ride Snapshot (to QBot)

```json
{
  "schemaVersion": 1,
  "userIntent": "manual_advice",
  "maxResponseChars": 160,
  "locale": "pl-PL",
  "rideSnapshot": {
    "elapsedSec": 3600,
    "distanceKm": 24.5,
    "speed": { "currentKmh": 24.0, "avgGrossKmh": 22.5, "avgMovingKmh": 24.2 },
    "power": { "currentW": 180, "npWatts": 210, "ifWholeRide": 0.82, "viValue": 1.05 },
    "hr": { "currentBpm": 142, "maxBpm": 180 },
    "cadence": { "currentRpm": 65 },
    "grade": { "currentPct": 3.2, "source": "OK", "reason": "light_climb" },
    "wPrime": { "balancePct": 72, "modelReady": true, "source": "local_model" },
    "rsrv": { "reservePct": 85, "modelReady": true, "source": "local_model" },
    "eta": { "etaTimestamp": 1716584400000, "modelReady": true, "source": "local_model" },
    "carb": { "rateGph": 65, "intakeTotalG": 120, "balanceG": 45, "modelReady": true },
    "fluid": { "rateLph": 0.6, "modelReady": true },
    "ascent": { "doneM": 320, "leftM": 880, "hasRoute": true },
    "battery": { "headunitPct": 74, "drainPctPerHour": 8.2, "source": "headunit_polling" },
    "route": { "loaded": true, "source": "NAV", "climbs": 7 },
    "sensors": {
      "powerFresh": true, "hrFresh": true, "cadenceFresh": true,
      "speedFresh": true, "gearPresent": false
    },
    "lastActiveMsg": null,
    "weather": {
      "fresh": false, "temperatureC": null, "windSpeedMps": null,
      "rain1hMm": null, "condition": null
    }
  }
}
```

### Snapshot rules
- Include only fields with known readiness/status.
- If model NOT ready: field omitted or `"modelReady": false`.
- If weather NOT fresh: `"fresh": false`, fields null.
- If gear NOT present: `"gearPresent": false`.
- No secrets, no API keys, no tokens.
- `lastActiveMsg` = null if no active message (overlay GONE), else `{title, severity, createdAtMs}`.
- Total payload size: estimated < 2KB.

## 4. QBot API Contract Candidate

```
Endpoint: POST https://qbot.cytr.us/qext/advice
Headers: Content-Type: application/json
Timeout: 15s (client enforced)
```

### Request
```json
{
  "schemaVersion": 1,
  "requestId": "uuid",
  "requestedAtMs": 1716584400000,
  "userIntent": "manual_advice",
  "maxResponseChars": 160,
  "locale": "pl-PL",
  "rideSnapshot": { ... }
}
```

### Response (success)
```json
{
  "ok": true,
  "requestId": "uuid",
  "adviceText": "Zwolnij tempo, podjazd za 3 km. Masz 72% W' — oszczędź.",
  "severity": "INFO",
  "category": "pacing",
  "confidence": 0.85,
  "ttlSec": 300,
  "createdAtMs": 1716584415000,
  "warnings": []
}
```

### Response (error/silence)
```json
{
  "ok": false,
  "requestId": "uuid",
  "adviceText": null,
  "error": "rate_limited"
}
```

### Response rules
- `adviceText` max 160 chars (client truncates if longer)
- `ttlSec` — client discards response after TTL expiry
- `confidence` 0.0–1.0 — not shown to user, optionally colored
- `category` — maps to optional small label in UI
- `severity` — maps to color (INFO=blue, WARNING=amber, CRITICAL=red)

## 5. Failure Behavior

| scenario | UI state | log | crash |
|---|---|---|---|
| Network unavailable | "BRAK ODPOWIEDZI" | `QEXT_ADVICE_FAILED reason=network_unavailable` | NO |
| Timeout (15s) | "BRAK ODPOWIEDZI" | `QEXT_ADVICE_FAILED reason=timeout` | NO |
| HTTP != 200 | "BRAK ODPOWIEDZI" | `QEXT_ADVICE_FAILED reason=http_status status=...` | NO |
| Malformed JSON | "BRAK ODPOWIEDZI" | `QEXT_ADVICE_FAILED reason=parse_error` | NO |
| `ok=false` | "BRAK ODPOWIEDZI" | `QEXT_ADVICE_FAILED reason=server_error error=...` | NO |
| TTL expired | Discard, show "ODPYTAJ" | `QEXT_ADVICE_STALE reason=ttl_expired` | NO |
| Advice text empty/null | "BRAK ODPOWIEDZI" | `QEXT_ADVICE_FAILED reason=empty_response` | NO |
| Sneaky malformed advice (>500 chars) | Truncate to 160 chars | `QEXT_ADVICE_TRUNCATED original=...` | NO |

### Non-blocking
- Primary field (LIVE 3x2) MUST continue rendering during ask+response.
- STATS field MUST continue updating.
- ACTIVE overlay MUST NOT be suppressed or overridden by Ride Ask response.
- No fake advice — never synthesize advice locally.

## 6. Security & Privacy

| check | rule |
|---|---|
| Secrets in payload | NO API keys, tokens, or passwords in snapshot |
| Secrets in logs | NO — response and request bodies summarized, not dumped |
| Response length cap | 500 chars hard limit prior to truncation |
| Request log | `QEXT_ADVICE_REQUEST_START requestId=...` (no body) |
| Response log | `QEXT_ADVICE_RESPONSE status=200 adviceLen=...` (no body text) |
| Snapshot data | Only ride telemetry and status fields |
| User location | NOT sent unless user opts in (not part of snapshot) |

## 7. Architecture

### New classes

| class | role | dependencies |
|---|---|---|
| `RideAdviceDataType` | DataTypeImpl `qext2-advice` | `QExt2PrimaryExtension`, `RideDataAggregator` |
| `RideAdviceRenderer` | Binds advice state to RemoteViews | `R.id.*`, severity colors |
| `RideAdviceClient` | HTTP client for QBot advice | `OnHttpResponse`, `BuildConfig` |
| `RideAdviceSnapshotBuilder` | Builds snapshot JSON from aggregator | `RideDataAggregator`, `StatsRideSnapshot` |
| `RideAdviceState` | Sealed class: Idle, Sending, Response, Error | N/A |
| `RideAdviceStateStore` | Holds current advice state + TTL | N/A |
| `RideAdviceConfig` | Timeout, TTL, max chars, endpoint URL | `BuildConfig` |

### Data flow

```
[User tap ODPYTAJ] -> RideAdviceDataType
  -> RideAdviceSnapshotBuilder.build(aggregator, weather) -> JSON
  -> RideAdviceClient.request(snapshot) HTTP POST -> QBot
  -> response (or error/timeout) -> RideAdviceStateStore
  -> RideAdviceRenderer.bind(views, state)
```

### Layout

`field_advice_2x1.xml`:
- `tv_advice_text` (main advice, 2 lines max)
- `tv_advice_meta` (timestamp + severity label, optional)
- `tv_advice_action` (tap target "ODPYTAJ" / "PYTAM...")

### Existing systems — no changes
- `RideDataAggregator` — read-only access to `statsSnapshot` / getter methods
- `CompositePrimaryDataType` — untouched
- `StatsDataType` — untouched
- `CompositeActiveDataType` — untouched

## 8. Tests

### Unit tests (JVM)

```
RideAdviceSnapshotBuilderTest:
  - snapshot_includes_all_ready_fields
  - snapshot_omits_not_ready_fields
  - snapshot_marks_model_not_ready_when_unavailable
  - snapshot_weather_null_when_not_fresh
  - snapshot_no_secrets_in_output
  - snapshot_size_under_2kb

RideAdviceClientTest (mock HTTP):
  - client_success_parses_response
  - client_timeout_no_crash
  - client_http_error_no_crash
  - client_malformed_json_no_crash
  - client_ok_false_handled
  - client_advice_too_long_truncated
  - client_does_not_log_secrets

RideAdviceStateStoreTest:
  - state_transitions_idle_to_sending_to_response
  - state_transitions_sending_to_error
  - ttl_expiry_discards_response
  - debounce_prevents_double_send

RideAdviceRendererTest:
  - renderer_idle_shows_odpytaj
  - renderer_sending_shows_pytam
  - renderer_response_shows_advice_text
  - renderer_error_shows_brak_odpowiedzi
  - renderer_severity_colors_correct

Integration contract tests:
  - primary_stats_active_not_blocked_by_advice_request
  - advice_request_does_not_modify_ride_state
```

### Virtual replay scenarios

```
  - scenario_advice_idle_start
  - scenario_advice_request_and_response
  - scenario_advice_timeout_graceful
  - scenario_advice_network_failure
  - scenario_advice_does_not_block_primary
```

## 9. Replay / Simulation

Test without Karoo:
- Mock `RideDataAggregator` with pre-built `StatsRideSnapshot`
- Mock `RideAdviceClient` with canned JSON responses
- Verify `RideAdviceRenderer` transitions
- Timeout: mock HTTP that sleeps longer than 15s
- No network: mock HTTP that throws `IOException`
- Stale data: mock response with TTL=1s, wait 2s, verify discarded

## 10. Implementation Phases

### Phase 0 — Contract + Mock Tests (this doc + JVM only)
- [x] `docs/QEXT2_RIDE_ASK_PREDESIGN.md`
- [ ] Contract test suite (JVM, no Karoo)
- [ ] Mock client + snapshot builder + renderer tests
- [ ] All tests PASS on `./gradlew test`

### Phase 1 — Local Mock Client + Renderer
- [ ] `RideAdviceClient` with mock mode (uses local canned responses)
- [ ] `RideAdviceSnapshotBuilder` (real aggregator integration)
- [ ] `RideAdviceDataType` + `RideAdviceRenderer` + layout
- [ ] `QEXT_ADVICE_*` log tags
- [ ] Static Karoo smoke: verify mock response renders

### Phase 2 — Real HTTP Client (gated behind config)
- [ ] `RideAdviceClient` real HTTP behind `BUILD_CONFIG_RIDE_ADVICE_ENABLED=false`
- [ ] Timeout + error handling verified on Karoo
- [ ] No QBot endpoint required — can test with `httpbin.org/post` or similar

### Phase 3 — QBot Endpoint Integration
- [ ] Configure `QEXT_RIDE_ADVICE_URL` -> `https://qbot.cytr.us/qext/advice`
- [ ] Real integration test with QBot backend
- [ ] Contract verification (request/response format match)

### Phase 4 — Karoo Static Smoke
- [ ] Install on Karoo, verify snapshot builds, verify mock/real response renders
- [ ] Verify primary/STATS/ACTIVE not blocked

### Phase 5 — Field Validation
- [ ] Real ride with network, manual advice request
- [ ] Verify response usefulness, latency, TTL behavior
- [ ] Export logs for review

## 11. Risks

| risk | mitigation |
|---|---|
| High latency (>15s) | Client timeout 15s, UI shows "PYTAM..." with cancel option |
| Network dependency | Offline fallback to "BRAK ODPOWIEDZI" — no fake advice |
| Overlong advice | Client truncates to 160 chars |
| Irrelevant advice | Severity/category labels help user assess; QBot side improve |
| Stale data (snapshot not fresh) | Snapshot includes sensor freshness; QBot sees it |
| User distraction | Non-intrusive 2x1 field, no pop-up, no overlay |
| Rate limiting abuse | Client-side debounce 15s; QBot-side rate limiting |
| Malformed response crash | All parsing wrapped in try-catch, fallback to error state |

## 12. Hard Rules

- Ride Ask nie może aktualizować primary field (SPEED/POWER/HR/CADENCE/GRADE/GEAR).
- Ride Ask nie może pisać do `RideState`, `StatsCalculator`, ani `RideDataAggregator`.
- Ride Ask nie może robić automatycznych zapytań w pętli — tylko manual trigger.
- Rate limit: minimum 15s between requests (client-side debounce).
- Brak odpowiedzi = fallback do error state, NIGDY zmyślona rada lokalnie.
- Response TTL expired = revert to idle, nie trzymać starej rady.
- Wszystkie logi redagowane — brak surowych request/response body.
- Nowy endpoint URL trzymany w `BuildConfig`, konfigurowalny przez `local.properties`.
