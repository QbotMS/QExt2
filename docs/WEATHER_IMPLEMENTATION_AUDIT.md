# Weather Implementation Audit

## OpenWeatherMap API Key

- **key_found**: false (initial search), then **configured** in `local.properties`
- **key_configured**: YES (via `local.properties` OPENWEATHER_API_KEY)
- **key_stored_in**: `local.properties` (NOT hardcoded in Kotlin source)
- **key_logged**: NO (logs contain `QEXT_WEATHER_FETCH_START lat=... lon=...` only, no key)
- **variable_name**: `OPENWEATHER_API_KEY`

## Karoo Smoke (2026-05-24 ~23:13)

- `QEXT_SELF_CHECK PASS`
- `QEXT_WEATHER_FETCH_START lat=52.2297 lon=21.0122` (location loaded from `local.properties` defaults)
- `QEXT_WEATHER_FETCH_FAILED reason=exception` — brak aktywnej trasy sieciowej z Karoo w środowisku testowym. Karoo może łączyć się przez Companion App / telefon albo Wi-Fi.
- Weather fetch wymaga dostępnej sieci; przy jej braku failuje bezpiecznie — nie crashuje, nie generuje fake alertów

## Implementation Summary

### Configuration
- `OPENWEATHER_API_KEY` in `local.properties` (BuildConfig field)
- `OPENWEATHER_BASE_URL` default: `https://api.openweathermap.org/data/2.5/weather`
- API key NOT hardcoded in source; loaded from `local.properties` with empty default
- Without key: WeatherClient.isKeyConfigured() returns false, weather polling disabled

### Weather Client (`com.qext2.primary.weather.WeatherClient`)
- Fetches current weather via HTTPS GET from OpenWeatherMap
- Input: lat/lon (from AthleteDataStore location)
- Output: `WeatherData` with temperatureC, feelsLikeC, windSpeedMps, windDirectionDeg, humidityPct, rain1hMm, snow1hMm, condition
- Freshness: `isFresh()` checks `updatedAt` within 30 min window
- Failure modes: `openweather_key_missing`, `http_status`, `exception` with reason

### Integration
- `QExt2PrimaryExtension.startWeatherPolling()` polls every 10 min (600s) when key configured
- `RideDataAggregator.updateWeather()/fetchWeatherIfNeeded()` stores data in atomic refs
- `StatsRideSnapshot` has weather fields with readiness: `weatherSourceReady`, `weatherFresh`
- Location stored in `AthleteDataStore` (saveLocation/loadLocationLat/loadLocationLon)
- Without location: weather fetch returns null (no fake data)

### ACTIVE MSG Weather Producer (`WeatherMessageProducer`)
- Produces: rain, wind, heat, cold alerts
- Thresholds: rain > 1mm/h, wind > 8m/s, heat > 35°C, cold < 0°C
- Source/reason logged per trigger
- Guards: weather must be fresh; no default values produce alerts
- Cooldowns per type to prevent spam

### Tests
- `WeatherClientTest`: 10 tests covering freshness, key check, producer behavior
- `ArchitectureContractsTest`: includes `noActiveNgrokUrl` check
