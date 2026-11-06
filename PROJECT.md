# Project: CloudStream Subtitle Enrichers Bugfix & Reliability

## Architecture
- Module/package boundaries:
  - `KitsunekkoSubtitleEnricher`: KitsunekkoScraper, KitsunekkoHook, ZipSubtitleExtractor, KitsunekkoLocalServer, AssToSrtConverter.
  - `Anime47SubtitleEnricher`: Anime47Scraper, Anime47Hook, Anime47Auth, Anime47Matcher.
  - `StremioSubtitleEnricher`: StremioSubHook, StremioSubScraper.
  - `tests/`: Automated JVM test harnesses, packaging verification, coexistence suites.
- Provider Chain:
  - BaseProvider -> Anime47Hook -> StremioSubHook -> KitsunekkoHook (or any permutation order).

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Kitsunekko Local HTTP Loopback Server | Serve cached/extracted subtitles over 127.0.0.1:<port> to avoid OkHttp file:// scheme rejection in ExoPlayer | M1 | survey (R1) |
| 2 | Kitsunekko ASS/SSA to SRT & UTF-8 Converter | Convert ASS/SSA dialog cues and Shift-JIS/UTF-16 charsets to valid UTF-8 SubRip format for ExoPlayer CustomDecoder | M1 | survey (R1) |
| 3 | Kitsunekko Subtitle Offset Recognition | Populate non-empty cues in CustomDecoder so FullScreenPlayer.showSubtitleOffsetDialog recognizes loaded subtitles | M1 | survey (R1) |
| 4 | Anime47 Thread-Safe In-Memory Caching | Implement LRU caches for subtitle results, anime IDs, and episode lists in Anime47Scraper | M2 | survey (R2) |
| 5 | Anime47 Concurrency Storm Guard | Implement isScrapingInProgress AtomicBoolean guard & SupervisorJob in Anime47Hook.loadLinks | M2 | survey (R2) |
| 6 | Anime47 Thread-Safe Auth & Token Synchronization | Protect token refresh and login with Mutex and @Volatile fields in Anime47Auth | M2 | survey (R2) |
| 7 | Anime47 Response Body Double-Read Fix | Eliminate closed-stream IllegalStateException in safeText() and ensure single response body consumption | M2 | survey (R2) |
| 8 | Anime47 Episode Fallback Parsing | Parse episode number from data regex when cached episode number is null instead of hardcoding episode 1 | M2 | survey (R2) |
| 9 | Anime47 Fast Exit on Subtitles Emitted | Break out of activeScrapeJobs.join() once subtitleScraped is true | M2 | survey (R2) |
| 10 | Stremio SubHook Mock Collision Fix | Scope hook check in StremioSubHook.kt to avoid false rejection of test mock providers | M3 | survey (R3) |
| 11 | Plugin DEX & .cs3 Rebuild & Packaging | Rebuild DEX and .cs3 artifacts for all modified enrichers and verify plugins.json/repo.json | M3 | survey (R3) |
| 12 | End-to-End Coexistence & Regression Verification | Verify all automated tests across tests/ pass 100% without regression | M3 | survey (R3) |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | Kitsunekko Subtitle Loading and Display | KitsunekkoLocalServer, AssToSrtConverter, KitsunekkoScraper URL emission | none | IN_PROGRESS |
| 2 | Anime47 Subtitle Scanning Reliability | Anime47Scraper caching & safeText, Anime47Hook concurrency guard & fallback, Anime47Auth Mutex | none | PLANNED (WAITING_USER_COMMAND_AFTER_M1) |
| 3 | System Packaging, Coexistence & Non-Regression | StremioSubHook fix, plugin rebuilds, DEX verification, all test suites pass | M1, M2 | PLANNED |

*Note: Per user instruction update (2026-11-05T20:25:12Z), upon completing Milestone 1 and its verification/audit, pause and report completion to the user, and do NOT proceed to Milestone 2 until explicit user command is given ("Thực hiện xong milestone 1 thì dừng đợi lệnh thực hiện tiếp").*

## Code Layout
- `KitsunekkoSubtitleEnricher/KitsunekkoEnricher/src/main/kotlin/com/kitsunekko/enricher/`
  - `KitsunekkoScraper.kt`
  - `KitsunekkoHook.kt`
  - `ZipSubtitleExtractor.kt`
  - `KitsunekkoLocalServer.kt`
  - `AssToSrtConverter.kt`
- `Anime47SubtitleEnricher/Anime47Enricher/src/main/kotlin/com/anime47/enricher/`
  - `Anime47Scraper.kt`
  - `Anime47Hook.kt`
  - `Anime47Auth.kt`
- `StremioSubtitleEnricher/StremioEnricher/src/main/kotlin/com/stremio/subenricher/`
  - `StremioSubHook.kt`
- `tests/`
  - `test_e2e_coexistence.py`
  - `run_kotlin_e2e.py`
  - `test_packaging_boundary.py`
  - `run_adversarial_integration.py`
  - `run_adversarial_stress.py`
