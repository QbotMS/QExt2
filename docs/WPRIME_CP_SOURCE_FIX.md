### Szczegóły źródła CP/W′:

- Lokalny serwer (nie QBot): `BuildConfig.QEXT_READINESS_URL` -> `https://ankle-wool-undusted.ngrok-free.dev/ride-readiness`
- CP (`ltpWatts`) i W′ capacity (`wPrimeKj`) sa parsowane z JSON:
  - `wPrimeKj = json.optDouble("wPrimeKj", 3.75)`
  - `ltpWatts = json.optInt("ltpWatts", 0)`
- Dane trafiaja do `AthleteDataStore` (SharedPreferences), potem `AthleteData` -> `RideDataAggregator.applyAthleteData()`
- W `StatsCalculator`: `setWPrimeParams(wPrime, ltp)` wywolywane tylko gdy BOTH `wPrimeKj > 0` i `ltpWatts > 0`
- **Fix 2026-05-24**: `StatsCalculator` domyslne `wPrimeKj` i `ltpWatts` zmienione z `21.3f/192f` na `0f/0f` — bez potwierdzonych danych z serwera model W′ pozostaje `WAIT/NO_MODEL`
- W′ connector do QBot: **NIE ISTNIEJE** — dane pochodza z wlasnego endpointu REST, nie z QBot

### SSE endpoint is not readiness JSON

- `https://qbot.cytr.us/sse` was confirmed by server operator to return:
  - `Content-Type: text/event-stream`
  - `event: endpoint`
  - `data: /messages/?session_id=...`
- This is an SSE/MCP transport stream, NOT a one-shot JSON response.
- **QExt2 readiness client** (`QExt2PrimaryExtension.fetchAthleteData`):
  - Uses `OnHttpResponse.MakeHttpRequest(method = "GET", url = url)` — Karoo SDK standard HTTP
  - Expects `HttpResponseState.Complete` with `body` → `JSONObject(String(body))`
  - Does NOT handle `text/event-stream`
  - Does NOT implement SSE parser
- **Conclusion**: `/sse` is **not compatible** with the current QExt2 readiness client.
- **Required for W′**: endpoint returning `Content-Type: application/json` with fields `wPrimeKj` and `ltpWatts` (at minimum).

### Active endpoint (2026-05-24)

- **QEXT_READINESS_URL**: `https://qbot.cytr.us/ride-readiness`
- **Status**: ACTIVE, confirmed working (Karoo smoke 2026-05-24 ~22:31)
- **Confirmed fields**: `wPrimeKj=21.1`, `ltpWatts=193.7`, `ftpWatts=246`
- **Previous (ngrok)**: `https://ankle-wool-undusted.ngrok-free.dev/ride-readiness` — DEPRECATED
- **/sse**: NOT USED for readiness (SSE stream, incompatible)
- **/mcp**: separate endpoint, NOT USED by QExt2

- **Status**: CANDIDATE_ONLY (not active, not configured)
- **Test date**: 2026-05-24 ~21:11 CEST
- **DNS**: resolves OK (`104.21.2.240`, Cloudflare)
- **HTTP (port 80)**: 301 redirect to HTTPS
- **HTTPS (port 443)**: connection timeout (10s), 0 bytes received
- **Endpoint reachability**: FAILED_FROM_LOCAL_TEST (timeout)
- **Contract verified**: NO
- **Parser compatible**: UNKNOWN (cannot verify response format; name "sse" suggests Server-Sent Events, current client expects one-shot JSON)
- **Config changed**: NO (QEXT_READINESS_URL unchanged, still `ngrok-free.dev/ride-readiness`)
- **Safe behavior**: without valid server response, W′ remains `WAIT/NO_MODEL` (CP/W' defaults `0/0` in StatsCalculator)

### Required before switching QEXT_READINESS_URL

- [ ] endpoint reachable from Karoo/QExt2 environment (verify via adb shell curl or equivalent)
- [ ] content-type known (JSON `application/json` expected by current `OnHttpResponse` client; SSE requires new parser)
- [ ] response contains `wPrimeKj` and `ltpWatts` in JSON-compatible format, OR `fetchAthleteData` parser updated
- [ ] no secrets in logs (`QEXT_READINESS_URL` and token must stay in BuildConfig only)
- [ ] tests updated (`wprime_server_response_ok` / `wprime_sse_not_supported` / contract guard)
- [ ] static smoke PASS after URL change
