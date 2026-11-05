# Kế hoạch & Tài liệu Kỹ thuật: Kitsunekko Japanese Subtitle Enricher cho CloudStream

## 1. Tổng quan Dự án & Mục tiêu Cốt lõi
* **Tên dự án:** Kitsunekko Japanese Subtitle Enricher (Extension CloudStream 3)
* **Thư mục dự án:** `KitsunekkoSubtitleEnricher/`
* **Mục tiêu:** Tự động tìm kiếm, bóc tách và tiêm phụ đề tiếng Nhật nguyên bản (Japanese Softsubs: `.ass`, `.srt`, `.ssa`) từ trang kho phụ đề anime nổi tiếng **kitsunekko.net** trực tiếp vào trình phát CloudStream (đặc biệt khi xem qua **Torrentio, TorraStream, StremioX, AnimePahe...**).
* **Đối tượng phục vụ:** Người học tiếng Nhật (Shadowing, Anki, đọc Kanji song ngữ) và các fan anime muốn thưởng thức nguyên bản tiếng Nhật.
* **Bản chất kỹ thuật:**
  * Kế thừa mô hình **Dynamic Subtitle Enricher / Hook Provider** từ Anime47 Subtitle Enricher.
  * Tự động nhận diện Anime, phân tích Season/Episode, truy vấn kho thư mục của Kitsunekko.
  * **Xử lý đặc biệt đối với gói nén `.zip`:** Kitsunekko thường nén toàn bộ phụ đề một mùa trong file `.zip`. Extension sẽ tự động tải vào cache tạm, dùng `ZipInputStream` giải nén đúng tập người dùng đang xem và cấp đường dẫn `file://...` cục bộ cho trình phát CloudStream.

---

## 2. Khảo sát & Phân tích Chi tiết Cấu trúc Kitsunekko.net

Tham khảo trực tiếp logic xử lý từ `c:\Users\taoka\Documents\ca_nhan\trans_a\app\src\main\java\com\transa\anisub\subtitle\provider\KitsunekkoProvider.kt`:

### 2.1. Thư mục Gốc & Phân cấp Thư mục
* **Root URL:** `https://kitsunekko.net/dirlist.php?dir=subtitles%2Fjapanese%2F`
* **Cấu trúc liên kết thư mục anime:**
  * Regex nhận diện folder:
    ```regex
    <a href="(/dirlist\.php\?dir=[^"]+japanese%2F[^"]+%2F)"[^>]*>(?:<strong>)?([^<]+)
    ```
  * Trích xuất: Đường dẫn thư mục `href` (ví dụ: `/dirlist.php?dir=subtitles%2Fjapanese%2FFrieren%2F`) và tên anime hiển thị `title` (ví dụ: `Sousou no Frieren`).
* **Cơ chế Caching Thư mục Gốc (Directory Cache):**
  * Trang gốc chứa hơn 3.000 thư mục anime tiếng Nhật, kích thước HTML tương đối lớn (~200KB - 500KB).
  * Giải pháp: Lưu danh sách `List<Folder>` vào bộ nhớ đệm (In-memory Cache) và SharedPreferences trong 24 giờ. Chỉ tải lại khi người dùng bấm "Làm mới danh mục" trong Cài đặt hoặc hết hạn cache.

### 2.2. Phân cấp Tệp Phụ đề bên trong Thư mục Anime
* **URL thư mục anime:** `https://kitsunekko.net/dirlist.php?dir=subtitles%2Fjapanese%2F{encoded_folder_name}%2F`
* **Regex nhận diện file phụ đề:**
  ```regex
  <a href="((?:subtitles|/subtitles)[^"]+\.(?:ass|srt|ssa|zip))"
  ```
* **Hai định dạng xuất hiện trên Kitsunekko:**
  1. **File phụ đề đơn lẻ (`.ass`, `.srt`, `.ssa`):**
     * URL trực tiếp: `https://kitsunekko.net/{path}` (cần xử lý chuẩn hóa khoảng trắng `%20`).
     * Nhận diện tập qua hàm trích xuất `extractEpisodeNumber(fileName)`.
     * Nếu khớp với tập người dùng đang xem $\rightarrow$ Nạp trực tiếp URL vào `SubtitleFile`.
  2. **File nén nguyên mùa (`.zip`):**
     * Thường có dạng: `[Fansub] Frieren 01-28.zip` hoặc `Bocchi the Rock batch.zip`.
     * Cần quy trình bung nén ngầm (xem mục 3).

### 2.3. Giải pháp Kỹ thuật Trọng yếu: Xử lý File Nén `.zip` & Bảng mã (Encoding)

#### A. Vấn đề giải nén trong CloudStream
* Trình phát CloudStream (ExoPlayer) không thể nhận luồng `.zip` làm phụ đề.
* **Quy trình xử lý tự động của Extension:**
  1. Tải byte file `.zip` vào thư mục tạm `context.cacheDir.resolve("kitsunekko_cache")`. Tái sử dụng file nếu đã tải trước đó (tránh tải lại 20MB zip khi chuyển tập tiếp theo).
  2. Dùng luồng `ZipInputStream` lướt qua các entry trong file zip mà **không cần bung toàn bộ file zip ra đĩa**.
  3. Lọc entry có tên chứa số tập khớp với tập đang xem (`extractEpisodeNumber(entryName) == currentEpisode`).
  4. Trích xuất nội dung file phụ đề đó, lưu ra file tạm: `context.cacheDir/kitsunekko_subs/ep_{num}.ass`.
  5. Trả về đường dẫn cục bộ cho trình phát CloudStream:
     ```kotlin
     SubtitleFile(
         lang = "Tiếng Nhật (Kitsunekko)",
         url = "file://${extractedFile.absolutePath}"
     )
     ```

#### B. Vấn đề Bảng mã Ký tự (Charset Encoding)
* Nhiều phụ đề tiếng Nhật đời cũ trên Kitsunekko sử dụng bảng mã `Shift-JIS` hoặc `Windows-1252`, nếu đọc mặc định bằng UTF-8 sẽ bị lỗi font (mojibake).
* **Giải pháp:**
  * Giải mã thử nghiệm với `UTF-8`. Nếu phát hiện ký tự lỗi `\uFFFD`, tự động thử lại với `Charset.forName("Shift_JIS")` hoặc `Windows-1252`.
  * Chuẩn hóa dòng ngắt (`\r\n` $\rightarrow$ `\n`) và xóa bỏ ký tự BOM (`\uFEFF`).

---

## 3. Kiến trúc Module & Chi tiết Thiết kế Mã nguồn (Kotlin)

```text
KitsunekkoSubtitleEnricher/
├── build_plugin.py                              # Tool tự động biên dịch sang DEX và đóng gói .cs3
├── build.gradle.kts                              # Cấu hình Gradle CloudStream
├── settings.gradle.kts                          # Cấu hình project name
├── dist/                                        # Thư mục xuất xưởng (.cs3, plugins.json, repo.json)
└── KitsunekkoEnricher/
    └── src/main/kotlin/com/kitsunekko/enricher/
        ├── KitsunekkoPlugin.kt                  # Entrypoint, dọn dẹp cache, Settings Dialog
        ├── KitsunekkoHook.kt                    # Dynamic Hook (KitsunekkoEnrichedMainAPI)
        ├── KitsunekkoMatcher.kt                 # Bóc tách tên anime, Season Hint, trích xuất tập
        ├── KitsunekkoScraper.kt                 # Cào HTML Kitsunekko, duyệt thư mục, tải zip
        ├── utils/
        │   └── ZipSubtitleExtractor.kt          # Xử lý ZipInputStream, dò tập, xuất file tạm
        └── models/
            └── KitsunekkoModels.kt              # Data class Folder, SubtitleCandidate, MediaInfo
```

### 3.1. [KitsunekkoModels.kt] Data Models
* `KitsunekkoFolder`: Lưu thông tin thư mục anime (`href: String`, `title: String`).
* `KitsunekkoSubtitle`: Thông tin file phụ đề (`name`, `url`, `ext`, `isZip`, `episode`).
* `ParsedMediaInfo`: Dữ liệu bóc tách từ tên torrent/video (`cleanTitle`, `episodeNumber`, `seasonNumber`).

### 3.2. [KitsunekkoMatcher.kt] Thuật toán So khớp & Season Hint
Kế thừa thuật toán tinh vi từ `KitsunekkoProvider.kt`:
* **Nhận diện Season (Mùa):**
  * Hỗ trợ số La Mã: `II` $\rightarrow$ Season 2, `III` $\rightarrow$ Season 3, `IV` $\rightarrow$ Season 4...
  * Hỗ trợ cú pháp: `Season 2`, `2nd Season`, `Part 2`, `SS2`.
* **Cơ chế Phạt Điểm Mùa (Season Penalty):**
  * Nếu phim người dùng đang xem là Season 2 mà thư mục ứng viên là Season 1: Trừ 200 điểm (`score -= 200`).
  * Giúp phân biệt chính xác tuyệt đối các bộ nhiều mùa (ví dụ: *Jujutsu Kaisen* vs *Jujutsu Kaisen Season 2*).
* **Bóc tách số tập (`extractEpisodeNumber`):**
  * Ưu tiên dạng `S01E05` $\rightarrow$ 5.
  * Nhận diện tag `EP05`, `Tập 5`, `ep. 5`.
  * Nhận diện số tập độc lập (bỏ qua số độ phân giải như `720`, `1080` và các năm `1990..2030`).

### 3.3. [ZipSubtitleExtractor.kt] Động cơ Bung Nén An Toàn
* Sử dụng `java.util.zip.ZipInputStream` nguyên bản của Android SDK (không phát sinh thêm thư viện ngoài).
* Giới hạn an toàn (ZipSlip protection): Kiểm tra đường dẫn file không bị thoát khỏi thư mục tạm (`!entry.name.contains("..")`).
* Giữ tối đa 4 file zip trong bộ nhớ đệm (LRU cache trên disk), tự động xóa các file zip cũ khi vượt quá 100MB.

### 3.4. [KitsunekkoScraper.kt] Động cơ Cào Phụ đề
* **Bước 1:** Lấy danh sách folders (từ RAM cache hoặc cào `rootUrl`).
* **Bước 2:** Chạy `bestMatchScore` đối chiếu tên anime (bao gồm cả Romaji, English, Japanese Title nhận được từ Hook).
* **Bước 3:** Lấy tối đa 2 folder có điểm số cao nhất $\ge 40$.
* **Bước 4:** Tải HTML thư mục anime, dùng regex lọc ra các file `.ass`, `.srt`, `.zip`.
* **Bước 5:** Đối chiếu số tập:
  * Nếu là file `.ass`/`.srt`: nạp URL trực tiếp.
  * Nếu là file `.zip`: chuyển sang `ZipSubtitleExtractor` để bóc tách file tập tương ứng.

### 3.5. [KitsunekkoHook.kt] Dynamic Provider Hook
* Định danh class: `KitsunekkoEnrichedMainAPI`.
* Đánh chặn `load(url)`: Lưu `ShowInfo` (tên Romaji, tên Tiếng Nhật `japName`, tên Tiếng Anh `engName`, danh sách tập).
* Đánh chặn `loadLinks(...)`:
  * Lấy tên anime từ cache hoặc bóc tách từ tên torrent / ExtractorLink.
  * Kích hoạt `KitsunekkoScraper.searchSubtitles(...)`.
  * Tiêm vào player qua `subtitleCallback(SubtitleFile("Tiếng Nhật (Kitsunekko)", url))`.
  * Timeout bảo vệ 4.5s đảm bảo không làm đứng player.

### 3.6. [KitsunekkoPlugin.kt] Quản trị & Settings UI
* Cung cấp nút:
  * **Xóa bộ nhớ đệm phụ đề (Clear Cache):** Dọn dẹp thư mục `kitsunekko_cache` và `kitsunekko_subs`.
  * **Làm mới danh mục:** Buộc cào lại trang gốc `dirlist.php` nếu có anime mới phát hành.
  * **Thông tin dung lượng cache:** Hiển thị dung lượng file tạm đang sử dụng trên máy.

---

## 4. Cơ chế Hook Chaining (Tương thích Đa Extension)
* `KitsunekkoEnrichedMainAPI` chỉ bỏ qua nếu provider chính là nó hoặc mang tên `Kitsunekko`.
* Hoàn toàn tương thích khi chạy đồng thời với **Anime47 Subtitle Enricher** và **Stremio Subtitle Enricher**.
* Khi người dùng xem một tập anime trên **Torrentio**, danh sách phụ đề của trình phát sẽ tự động hiển thị đầy đủ:
  1. `Tiếng Việt (Anime47)` *(từ Anime47 Enricher)*
  2. `English (Stremio)` / `Tiếng Việt (Stremio)` *(từ Stremio Enricher)*
  3. `Tiếng Nhật (Kitsunekko)` *(từ Kitsunekko Enricher)*

---

## 5. Quy trình Đóng gói & Phát hành

1. **Biên dịch Kotlin $\rightarrow$ DEX:** Sử dụng `build_plugin.py` kết hợp Kotlin 2.1.0 và D8 Dexer.
2. **Đóng gói file `.cs3`:**
   * Manifest name: `KitsunekkoEnricher`
   * Plugin class: `com.kitsunekko.enricher.KitsunekkoPlugin`
3. **Phân phối:** Tích hợp file `KitsunekkoEnricher.cs3` vào file `plugins.json` và `repo.json` chung của kho lưu trữ.

---

## 6. Kế hoạch Triển khai Chi tiết từng Bước (Action Plan)

* [ ] **Bước 1:** Khởi tạo thư mục dự án `KitsunekkoSubtitleEnricher/` và các file cấu hình (`build.gradle.kts`, `build_plugin.py`).
* [ ] **Bước 2:** Xây dựng `KitsunekkoModels.kt` và chuyển thể `KitsunekkoMatcher.kt` với Season Hint, Roman Numerals và Regex Episode Extractor.
* [ ] **Bước 3:** Lập trình `ZipSubtitleExtractor.kt` với cơ chế đọc `ZipInputStream` theo luồng, auto-detect bảng mã (UTF-8 / Shift-JIS) và ghi file cache.
* [ ] **Bước 4:** Lập trình `KitsunekkoScraper.kt` tích hợp cào danh mục gốc, cache 24h và lọc file trong thư mục anime.
* [ ] **Bước 5:** Lập trình `KitsunekkoHook.kt` (KitsunekkoEnrichedMainAPI) và `KitsunekkoPlugin.kt` tích hợp Settings UI dọn dẹp cache.
* [ ] **Bước 6:** Biên dịch thử nghiệm ra `KitsunekkoEnricher.cs3`, kiểm tra trên CloudStream với cả hai trường hợp: file rời `.ass` và bộ nén `.zip`.

