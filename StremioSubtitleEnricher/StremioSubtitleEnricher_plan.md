# Kế hoạch & Tài liệu Kỹ thuật: Stremio Subtitle Enricher cho CloudStream

## 1. Tổng quan Dự án & Mục tiêu Cốt lõi
* **Tên dự án:** Stremio Subtitle Enricher (Extension CloudStream 3)
* **Thư mục dự án:** `StremioSubtitleEnricher/`
* **Mục tiêu:** Tự động trích xuất và tích hợp phụ đề đa ngôn ngữ (Tiếng Việt, Tiếng Anh, Tiếng Tây Ban Nha, Tiếng Pháp, Tiếng Nhật...) từ hệ sinh thái Stremio (bao gồm **Kitsu Anime Subtitles**, **OpenSubtitles v3**, bộ tra cứu metadata **Cinemeta**, cùng các **Custom Stremio Subtitle Addons**) trực tiếp vào trình phát CloudStream khi xem qua các nguồn phim như **Torrentio, TorraStream, StremioX, AnimePahe...**.
* **Bản chất kỹ thuật:**
  * Hoạt động theo mô hình **Dynamic Subtitle Enricher / Hook Provider** độc lập (kế thừa mô hình thành công của Anime47 Subtitle Enricher).
  * Lớp Hook (`StremioEnrichedMainAPI`) tự động đánh chặn tiến trình `load()` để lưu lại tên tác phẩm, danh sách tên phụ, năm phát hành, số tập và mùa.
  * Khi gọi `loadLinks()`, tiện ích sẽ kích hoạt song song tiến trình cào phụ đề từ Kitsu (nếu là anime) và OpenSubtitles v3 / Cinemeta (nếu là series/movies), sau đó tiêm danh sách phụ đề vào CloudStream player thông qua `subtitleCallback(SubtitleFile(lang, url))`.
  * Không làm gián đoạn hoặc tăng độ trễ khi tải video (luồng video vẫn phát tức thì, phụ đề nạp bất đồng bộ trong thời gian chờ tối đa 4.5s).

---

## 2. Khảo sát & Phân tích Chi tiết các Nguồn API Stremio

Tham khảo trực tiếp logic xử lý từ `c:\Users\taoka\Documents\ca_nhan\trans_a\app\src\main\java\com\transa\anisub\subtitle\provider\StremioSubProvider.kt`:

### 2.1. Phân hệ Kitsu Anime Subtitles
* **Base URL:** `https://anime-kitsu.strem.fun`
* **Cơ chế tìm kiếm Kitsu ID:**
  * Endpoint: `GET /catalog/anime/kitsu-search/search={keyword}.json`
  * Response: Danh sách `metas: [ { id: "kitsu:12345", name: "...", releaseInfo: "..." } ]`
  * Matching: Tính điểm tương đồng chuỗi `bestMatchScore(name, titles)` giữa tên trả về và danh sách tên phim. Ngưỡng chấp nhận $\ge 40$.
* **Cơ chế lấy phụ đề Kitsu:**
  * Endpoint Kitsu: `GET /subtitles/anime/kitsu%3A{kitsuId}%3A{ep}.json`
  * Endpoint MAL (nếu có MyAnimeList ID): `GET /subtitles/anime/mal%3A{malId}%3A{ep}.json`
  * Response: Danh sách `subtitles: [ { url: "...", lang: "eng", subtitleFileName: "...", episode: 5 } ]`

### 2.2. Phân hệ Cinemeta Resolver & OpenSubtitles v3
* **Cinemeta Base URL:** `https://v3-cinemeta.strem.io`
* **OpenSubtitles Base URL:** `https://opensubtitles-v3.strem.io`
* **Cơ chế tra cứu IMDb ID qua Cinemeta:**
  * Series: `GET /catalog/series/top/search={keyword}.json`
  * Movie: `GET /catalog/movie/top/search={keyword}.json`
  * Matching: Tìm mục có `id` bắt đầu bằng `tt...`, tính điểm so khớp tên kèm cộng điểm thưởng (+15) nếu năm phát hành `releaseInfo` khớp với năm của tác phẩm.
* **Cơ chế lấy phụ đề OpenSubtitles v3:**
  * Series: `GET /subtitles/series/{imdbId}%3A{season}%3A{ep}.json`
  * Movie: `GET /subtitles/movie/{imdbId}.json`
  * Tích hợp cơ chế tự động thử lại (Retry with Exponential Backoff) khi gặp HTTP 5xx hoặc timeout.

### 2.3. Phân hệ Custom Stremio Subtitle Addons
* Cho phép người dùng nhập bất kỳ URL addon phụ đề Stremio nào (ví dụ các addon phụ đề ngôn ngữ cụ thể, addon cá nhân hóa).
* Endpoint addon: `GET {addonBase}/subtitles/{type}/{imdbId}:{season}:{ep}.json`

### 2.4. Chuẩn hóa & Ánh xạ Mã Ngôn ngữ (Language Mapping)
* Chuyển đổi mã ISO 639-3 / 639-2 sang định dạng dễ đọc cho người dùng CloudStream:
  * `vie` $\rightarrow$ `Tiếng Việt (Stremio)`
  * `eng` $\rightarrow$ `English (Stremio)`
  * `jpn` $\rightarrow$ `Japanese (Stremio)`
  * `spa` $\rightarrow$ `Spanish (Stremio)`, `fra` $\rightarrow$ `French (Stremio)`, `deu` $\rightarrow$ `German (Stremio)`...
* Giữ nguyên mã 2 ký tự chuẩn hoặc tên hiển thị nguyên bản nếu không nằm trong danh mục ánh xạ.

---

## 3. Kiến trúc Module & Chi tiết Thiết kế Mã nguồn (Kotlin)

```text
StremioSubtitleEnricher/
├── build_plugin.py                          # Tool tự động biên dịch sang DEX và đóng gói .cs3
├── build.gradle.kts                          # Cấu hình Gradle CloudStream
├── settings.gradle.kts                      # Cấu hình project name
├── dist/                                    # Thư mục xuất xưởng (.cs3, plugins.json, repo.json)
└── StremioEnricher/
    └── src/main/kotlin/com/stremio/subenricher/
        ├── StremioSubPlugin.kt              # Entrypoint plugin, SharedPreferences, Settings Dialog
        ├── StremioSubHook.kt                # Dynamic Interceptor Hook (StremioEnrichedMainAPI)
        ├── StremioSubMatcher.kt             # Lọc tên torrent, regex episode, tính similarity score
        ├── StremioSubScraper.kt             # Pipeline cào Kitsu, Cinemeta, OpenSubtitles & Addons
        └── models/
            └── StremioModels.kt             # Data classes parse JSON kết quả
```

### 3.1. [StremioModels.kt] Data Models
* `StremioSub`: Đối tượng phụ đề đơn lẻ (`url`, `lang`, `lang_code`, `title`, `subtitleFileName`, `season`, `episode`).
* `StremioSubtitles`: Vỏ bọc danh sách `subtitles`.
* `KitsuMeta` & `KitsuCatalog`: Parse danh mục tìm kiếm Kitsu và Cinemeta.
* `ParsedMediaInfo`: Kết quả bóc tách chuỗi tiêu đề torrent (`cleanTitle`, `season`, `episode`, `year`).

### 3.2. [StremioSubMatcher.kt] Thuật toán So khớp & Bóc tách
* **Lọc nhiễu:** Bỏ tên nhóm phát hành (`[SubsPlease]`, `[Erai-raws]`), độ phân giải (`1080p`, `4K`), định dạng codec (`HEVC`, `x264`, `x265`), đuôi mở rộng (`.mkv`, `.mp4`).
* **Bóc tách mùa & tập:** Hỗ trợ `S01E05`, `E05`, `- 05`, `Episode 5`, `Tập 5`.
* **Trích xuất năm:** Regex `(19\d{2}|20\d{2})` để hỗ trợ đối chiếu với Cinemeta.
* **Tính điểm tương đồng (Similarity Scoring):**
  * Sử dụng thuật toán Bigram Dice Similarity kết hợp chuẩn hóa khoảng trắng/dấu câu (`normTitle`).
  * Trả về điểm từ $0 \rightarrow 100$.

### 3.3. [StremioSubScraper.kt] Động cơ Cào Phụ đề Bất đồng bộ
* Sử dụng client HTTP của CloudStream (`app.get` từ NiceHttp) với timeout tối ưu.
* **Bộ nhớ đệm (LRU Memory Cache):**
  * `resultCache`: Lưu kết quả tìm phụ đề theo key `kitsu:{id}:{ep}` hoặc `os:{imdbId}:{season}:{ep}` (tối đa 32 mục).
  * `kitsuIdCache`: Lưu mapping `tên phim -> kitsuId`.
  * `imdbCache`: Lưu mapping `tên phim -> imdbId`.
* **Thực thi song song (Coroutine Concurrency):**
  * Đồng thời tra cứu Kitsu và OpenSubtitles v3 qua `async { ... }`.
  * Gộp kết quả, lọc trùng lặp qua `seenUrls: MutableSet<String>`.
  * Bộ lọc ngôn ngữ: Chỉ nạp các ngôn ngữ người dùng đã bật trong Cài đặt (mặc định ưu tiên Tiếng Việt, Tiếng Anh và Tất cả).

### 3.4. [StremioSubHook.kt] Dynamic Provider Hook
* Quét `APIHolder.allProviders` và `APIHolder.apis`.
* Bọc các provider mục tiêu bằng `StremioEnrichedMainAPI`.
* **Đánh chặn `load(url)`:** Thu thập `name`, `engName`, `japName`, `synonyms`, danh sách `episodes`.
* **Đánh chặn `loadLinks(...)`:**
  * Lấy `ShowInfo` đã cache hoặc phân tích tham số `data`, magnet `&dn=`, filename video.
  * Kích hoạt `StremioSubScraper.fetchSubtitles(...)` trong background job.
  * Mỗi khi có phụ đề trả về, gọi ngay `subtitleCallback(SubtitleFile(lang, url))`.
  * Đồng bộ timeout 4.5s đảm bảo danh sách phụ đề được nạp vào Player trước khi luồng liên kết đóng.

### 3.5. [StremioSubPlugin.kt] Quản trị & Settings UI
* Màn hình cài đặt CloudStream:
  * **Lọc ngôn ngữ:** Tùy chọn `Tất cả`, `Ưu tiên Tiếng Việt & Tiếng Anh`, `Chỉ Tiếng Việt`...
  * **Danh sách Custom Addons:** Ô nhập đa dòng danh sách URL Stremio Addon.
  * **Kiểm tra trạng thái máy chủ:** Ping kiểm tra kết nối tới Kitsu & OpenSubtitles.

---

## 4. Cơ chế Hook Chaining (Tương thích Đa Extension)
* Định danh wrapper riêng: `StremioEnrichedMainAPI`.
* Hàm kiểm tra `shouldHook`:
  * Không hook nếu provider đã là `StremioEnrichedMainAPI` hoặc chính là `StremioSubEnricher`.
  * Cho phép hook nếu provider đã được bọc bởi `Anime47EnrichedMainAPI` hoặc `KitsunekkoEnrichedMainAPI`.
  * Tạo chuỗi liên kết an toàn: `Player -> Kitsunekko -> Stremio -> Anime47 -> Torrentio`. Cả 3 tiện ích đều tiêm phụ đề độc lập mà không can thiệp vào nhau.

---

## 5. Quy trình Đóng gói & Phát hành

1. **Biên dịch Kotlin $\rightarrow$ DEX:** Sử dụng `build_plugin.py` gọi Kotlin 2.1.0 và Google D8 dexer (minSdkVersion 21).
2. **Đóng gói file `.cs3`:** Chứa `classes.dex` và `manifest.json`.
   * Manifest name: `StremioSubEnricher`
   * Plugin class: `com.stremio.subenricher.StremioSubPlugin`
3. **Sinh metadata:** Tự động tạo mã băm SHA256, sinh `plugins.json` và `repo.json` tương thích với kho tiện ích CloudStream.

---

## 6. Kế hoạch Triển khai Chi tiết từng Bước (Action Plan)

* [ ] **Bước 1:** Khởi tạo thư mục dự án `StremioSubtitleEnricher/` và các cấu hình build (`build.gradle.kts`, `build_plugin.py`).
* [ ] **Bước 2:** Xây dựng `StremioModels.kt` và chuyển thể `StremioSubMatcher.kt` với thuật toán Dice Coefficient & Season/Episode Parser.
* [ ] **Bước 3:** Lập trình `StremioSubScraper.kt` tích hợp Kitsu API, Cinemeta Resolver, OpenSubtitles v3 và Custom Addons.
* [ ] **Bước 4:** Lập trình `StremioSubHook.kt` (StremioEnrichedMainAPI) với cơ chế quan sát liên tục và timeout 4.5s.
* [ ] **Bước 5:** Xây dựng `StremioSubPlugin.kt` tích hợp SharedPreferences và Settings Dialog chọn ngôn ngữ & thêm Addon.
* [ ] **Bước 6:** Biên dịch thử nghiệm ra `StremioSubEnricher.cs3`, kiểm tra hoạt động trên Torrentio với phim anime và phim lẻ quốc tế.

