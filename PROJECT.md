# Project: CloudStream Subtitle Enrichers Bugfix & Reliability

## Architecture
- Module/package boundaries:
  - `AiSubtitleTranslator`: `AITranslatorPlugin`, `AITranslatorHook`, `AITranslatorEnrichedMainAPI`, `AITranslationSession`, `AITranslatorSettingsDialog`, `TranslatorConfig`.
  - `KitsunekkoSubtitleEnricher`: `KitsunekkoScraper`, `KitsunekkoHook`, `KitsunekkoEnrichedMainAPI`, `ZipSubtitleExtractor`, `KitsunekkoLocalServer`, `AssToSrtConverter`.
  - `Anime47SubtitleEnricher`: `Anime47Scraper`, `Anime47Hook`, `EnrichedMainAPI`, `Anime47Auth`, `Anime47Matcher`.
  - `StremioSubtitleEnricher`: `StremioSubHook`, `StremioEnrichedMainAPI`, `StremioSubScraper`.
  - `tests/`: Automated JVM test harnesses, packaging verification, coexistence suites, adversarial integration & stress suites.
- Provider Chain:
  - `BaseProvider` -> `Anime47Hook` -> `KitsunekkoHook` -> `StremioSubHook` -> `AITranslatorEnrichedMainAPI`.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Kitsunekko Local HTTP Loopback Server | Serve cached/extracted subtitles over 127.0.0.1:<port> to avoid OkHttp file:// scheme rejection in ExoPlayer | M1 | survey (R1) [DONE] |
| 2 | Kitsunekko ASS/SSA to SRT & UTF-8 Converter | Convert ASS/SSA dialog cues and Shift-JIS/UTF-16 charsets to valid UTF-8 SubRip format for ExoPlayer CustomDecoder | M1 | survey (R1) [DONE] |
| 3 | Kitsunekko Subtitle Offset Recognition | Populate non-empty cues in CustomDecoder so FullScreenPlayer.showSubtitleOffsetDialog recognizes loaded subtitles | M1 | survey (R1) [DONE] |
| 4 | Anime47 Thread-Safe In-Memory Caching | Implement LRU caches for subtitle results, anime IDs, and episode lists in Anime47Scraper | M2 | survey (R2) [DONE] |
| 5 | Anime47 Concurrency Storm Guard | Implement isScrapingInProgress AtomicBoolean guard & SupervisorJob in Anime47Hook.loadLinks | M2 | survey (R2) [DONE] |
| 6 | Anime47 Thread-Safe Auth & Token Synchronization | Protect token refresh and login with Mutex and @Volatile fields in Anime47Auth | M2 | survey (R2) [DONE] |
| 7 | Anime47 Response Body Double-Read Fix | Eliminate closed-stream IllegalStateException in safeText() and ensure single response body consumption | M2 | survey (R2) [DONE] |
| 8 | Anime47 Episode Fallback Parsing | Parse episode number from data regex when cached episode number is null instead of hardcoding episode 1 | M2 | survey (R2) [DONE] |
| 9 | Anime47 Fast Exit on Subtitles Emitted | Break out of activeScrapeJobs.join() once subtitleScraped is true | M2 | survey (R2) [DONE] |
| 10 | Stremio SubHook Mock Collision Fix | Scope hook check in StremioSubHook.kt to avoid false rejection of test mock providers | M3 | survey (R3) [DONE] |
| 11 | Plugin DEX & .cs3 Rebuild & Packaging | Rebuild DEX and .cs3 artifacts for all modified enrichers and verify plugins.json/repo.json | M3 | survey (R3) [DONE] |
| 12 | End-to-End Coexistence & Regression Verification | Verify all automated tests across tests/ pass 100% without regression | M3 | survey (R3) [DONE] |
| 13 | 4-Layer Hook Chain URL Scheme Fix | Fix 'Expected URL scheme http or https' crash in AITranslatorEnrichedMainAPI -> StremioSubHook -> KitsunekkoHook -> Anime47Hook -> base provider (relative URL / mainUrl handling) | M8 | User Dispatch (Issue 1) [DONE] |
| 14 | Source List 4x Duplication Elimination | Prevent duplicate provider listings in CloudStream source list by correcting how hooks register/hoist in APIHolder.allProviders, APIHolder.apis, and pluginMapping | M8 | User Dispatch (Issue 2) [DONE] |
| 15 | Multi-Plugin Rebuild & Release Synchronization | Rebuild DEX and .cs3 for all 4 affected plugins, update plugins.json/repo.json, commit and push to main and builds | M8 | User Dispatch [DONE] |
| 16 | Live Device & Automated Coexistence Verification | Verify on live device 192.168.1.6:41739 via ADB and run all coexistence and packaging test suites | M8 | User Dispatch [DONE] |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | Kitsunekko Subtitle Loading and Display | KitsunekkoLocalServer, AssToSrtConverter, KitsunekkoScraper URL emission | none | DONE |
| 2 | Anime47 Subtitle Scanning Reliability | Anime47Scraper caching & safeText, Anime47Hook concurrency guard & fallback, Anime47Auth Mutex | none | DONE |
| 3 | System Packaging, Coexistence & Non-Regression | StremioSubHook fix, plugin rebuilds, DEX verification, all test suites pass | M1, M2 | DONE |
| 8 | 4-Layer Hook Chain Crash & Source Duplication Remediation | All 4 hooks (`AITranslatorHook`/`AITranslatorEnrichedMainAPI`, `StremioSubHook`, `KitsunekkoHook`, `Anime47Hook`), all providers, rebuild all 4 plugins, push to main and builds, verify on device 192.168.1.6:41739 | M1-M3 | DONE |
| 9 | Non-HTTP & JSON Payload Preservation in `load(url)` Across All 4 Enrichers | Fix `sanitizeUrl` & `sanitizeMainPageRequest` in all 4 wrapper classes, bump versions (AI v4, Anime47 v5, Kitsunekko v3, Stremio v3), full regression testing, git sync, live ADB verification | M8 | DONE |

## Code Layout
- `AiSubtitleTranslator/AiTranslator/src/main/kotlin/com/lagradost/cloudstream3/subtitles/ai/`
  - `AITranslatorPlugin.kt`
  - `hook/AITranslatorHook.kt`
  - `hook/AITranslatorEnrichedMainAPI.kt`
  - `session/AITranslationSession.kt`
  - `ui/AITranslatorSettingsDialog.kt`
  - `config/TranslatorConfig.kt`
- `Anime47SubtitleEnricher/Anime47Enricher/src/main/kotlin/com/anime47/enricher/`
  - `Anime47Hook.kt`
  - `Anime47Scraper.kt`
  - `Anime47Auth.kt`
- `KitsunekkoSubtitleEnricher/KitsunekkoEnricher/src/main/kotlin/com/kitsunekko/enricher/`
  - `KitsunekkoHook.kt`
  - `KitsunekkoScraper.kt`
  - `KitsunekkoLocalServer.kt`
  - `AssToSrtConverter.kt`
- `StremioSubtitleEnricher/StremioEnricher/src/main/kotlin/com/stremio/subenricher/`
  - `StremioSubHook.kt`
  - `StremioSubScraper.kt`
- `tests/`
  - `test_packaging_boundary.py`
  - `run_kotlin_e2e.py`
  - `test_e2e_coexistence.py`
  - `run_adversarial_integration.py`
  - `run_adversarial_stress.py`
  - `run_m8_challenger2_verification.py`
  - `run_adversarial_m8_challenger.py`
