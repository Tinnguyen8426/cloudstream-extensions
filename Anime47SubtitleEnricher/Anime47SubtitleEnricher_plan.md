# Kế hoạch & Tài liệu Kỹ thuật: Anime47 Subtitle Enricher cho CloudStream

## 1. Tổng quan dự án & Mục tiêu cốt lõi
* **Tên dự án:** Anime47 Subtitle Enricher (Extension CloudStream 3)
* **Mục tiêu:** Cho phép người dùng xem anime chất lượng cao (1080p, 4K, Remux) từ các extension quốc tế (như **Torrentio**, TorraStream, StremioX, AnimePahe...) nhưng vẫn tự động có phụ đề Tiếng Việt (Vietsub) được bóc tách và tiêm trực tiếp từ trang **anime47.best** (backend: `animevui.com/api`).
* **Bản chất kỹ thuật:**
  * Hoạt động như một **Dynamic Subtitle Enricher / Hook Provider** độc lập.
  * Khi người dùng mở anime trên các extension khác (Torrentio), tiện ích sẽ tự động nhận diện tên anime, số tập, truy vấn Anime47, trích xuất file phụ đề rời (**Softsub** định dạng `.vtt`), và nạp thẳng vào trình phát CloudStream dưới nhãn `Tiếng Việt (Anime47)`.
* **Tài khoản VIP mặc định:**
  * Email / Username: `thankhoaibe12@gmail.com`
  * Mật khẩu: `Matkhau@123`
  * Quản trị qua `Anime47Auth` và màn hình `Plugin Settings` (có nút khôi phục mặc định).

---

## 2. Toàn bộ các bước ĐÃ HOÀN TẤT (100%)

### ✅ Bước 1: Khảo sát & Phân tích cấu trúc API Anime47
* Toàn bộ Vue SPA của `https://anime47.best` thực chất giao tiếp với backend API tại `https://animevui.com/api`.
* Đã xác thực thành công và lập trình tích hợp các endpoint cốt lõi:
  1. **Đăng nhập (`POST /auth/login`):** Nhận payload `login`, `password`, `remember=true`. Trả về `access_token` (JWT Bearer), `refresh_token`, thời hạn 3600s.
  2. **Làm mới token (`POST /auth/refresh-token`):** Gửi `refresh_token`, cấp lại access token mới trước khi hết hạn 60s.
  3. **Tìm kiếm phim (`GET /search/full/?keyword={tên}&page=1`):** Trả về danh sách anime kèm danh sách `titles` đa ngôn ngữ (Synonyms, English, Romaji, Tiếng Việt).
  4. **Danh sách tập (`GET /anime/{id}/episodes`):** Header `Authorization: Bearer {token}` (vượt cơ chế bảo vệ `PRIVATE_MODE`). Trả về cấu trúc mảng `teams` $\rightarrow$ `groups` $\rightarrow$ `episodes`.
  5. **Lấy phụ đề softsub (`GET /anime/watch/episode/{id}`):** Trích xuất danh sách `streams` $\rightarrow$ `subtitles` $\rightarrow$ link WebVTT kèm chữ ký số HMAC (`.vtt?expires=...&signature=...`).

### ✅ Bước 2: Module Xử lý Chuẩn hóa Tên phim & Tập (Anime47Matcher)
* Lập trình tại [Anime47Matcher.kt](file:///data/data/com.termux/files/home/Anime47SubtitleEnricher/Anime47Enricher/src/main/kotlin/com/anime47/enricher/Anime47Matcher.kt):
  * **Regex lọc tên torrent:** Xóa bỏ tên nhóm phát hành (`[SubsPlease]`, `(HorribleSubs)`), tag độ phân giải/codec (`1080p`, `x265`, `HEVC`, `WEB-DL`, `Remux`...), mã hash CRC32 (`[7BAA1C90]`), định dạng đuôi file (`.mkv`, `.mp4`).
  * **Bóc tách Season & Episode:** Nhận diện các định dạng `S01E05`, `E05`, `- 05`, `Episode 5`, `Tập 5`.
  * **Thuật toán so khớp Dice Coefficient:** So sánh mức độ trùng khớp chuỗi giữa từ khóa torrent và tất cả các tên gọi của anime trên Anime47 (tên chính + alternative titles).
  * **Ánh xạ số tập:** Đối chiếu số tập người dùng đang xem sang đúng `episodeId` của Anime47.

### ✅ Bước 3: Lập trình Mã nguồn Extension bằng Kotlin
Đầy đủ mã nguồn chuẩn theo kiến trúc CloudStream:
* [Models.kt](file:///data/data/com.termux/files/home/Anime47SubtitleEnricher/Anime47Enricher/src/main/kotlin/com/anime47/enricher/models/Models.kt): Các data class cho Auth, Search, Episode, Subtitle.
* [Anime47Auth.kt](file:///data/data/com.termux/files/home/Anime47SubtitleEnricher/Anime47Enricher/src/main/kotlin/com/anime47/enricher/Anime47Auth.kt): Quản lý đăng nhập, SharedPreferences, làm mới token tự động.
* [Anime47Scraper.kt](file:///data/data/com.termux/files/home/Anime47SubtitleEnricher/Anime47Enricher/src/main/kotlin/com/anime47/enricher/Anime47Scraper.kt): Pipeline tìm kiếm đa tầng (Primary Title $\rightarrow$ Alternative Titles $\rightarrow$ Episode $\rightarrow$ Subtitle URL).
* [Anime47Hook.kt](file:///data/data/com.termux/files/home/Anime47SubtitleEnricher/Anime47Enricher/src/main/kotlin/com/anime47/enricher/Anime47Hook.kt): Dynamic Hook bao bọc `EnrichedMainAPI` đánh chặn `load()` và `loadLinks()`.
* [Anime47Provider.kt](file:///data/data/com.termux/files/home/Anime47SubtitleEnricher/Anime47Enricher/src/main/kotlin/com/anime47/enricher/Anime47Provider.kt): Standalone Provider cho phép duyệt và xem trực tiếp kho phim Anime47.
* [Anime47Plugin.kt](file:///data/data/com.termux/files/home/Anime47SubtitleEnricher/Anime47Enricher/src/main/kotlin/com/anime47/enricher/Anime47Plugin.kt): Điểm nạp plugin của CloudStream kèm giao diện Settings Dialog.

### ✅ Bước 4: Settings UI & Quản trị tài khoản
* Tích hợp `SharedPreferences` lưu trữ cấu hình email/mật khẩu lâu dài trong máy.
* `AlertDialog` Android cho phép người dùng tùy chọn đổi tài khoản, kiểm tra đăng nhập tức thì hoặc bấm "Khôi phục mặc định" tài khoản VIP có sẵn.

### ✅ Bước 5: Đóng gói & Bộ công cụ Biên dịch Release
* Thiết lập môi trường biên dịch tự động ngay trong Termux (JDK 17, Android SDK 34, Kotlin 2.1.0, Google D8 Dexer).
* Script tự động [build_plugin.py](file:///data/data/com.termux/files/home/Anime47SubtitleEnricher/build_plugin.py):
  * Tự động tải dependencies (`cloudstream.jar`, `nicehttp.jar`, `r8.jar`, `android.jar`).
  * Biên dịch mã nguồn Kotlin sang Java `.class` (JVM Target 11).
  * Dexing bằng Google D8 (minSdkVersion 21, hỗ trợ desugaring đầy đủ).
  * Đóng gói file extension chuẩn CloudStream `.cs3` (chứa `classes.dex` và `manifest.json`).
  * Tự động tính toán mã băm SHA256 và sinh ra file `plugins.json`, `repo.json`.
* Workflow GitHub Actions [.github/workflows/build.yml](file:///data/data/com.termux/files/home/Anime47SubtitleEnricher/.github/workflows/build.yml) và Gradle wrapper [gradlew](file:///data/data/com.termux/files/home/Anime47SubtitleEnricher/gradlew).

### ✅ Bước 6: Phân tích & Khắc phục Sự cố Thực tế trên CloudStream (Bản v1 $\rightarrow$ v2 $\rightarrow$ v3)

#### 1. Sự cố Cài đặt Local (CloudStream bỏ cài file từ bộ nhớ)
* **Vấn đề:** Các bản CloudStream mới không còn mục "Cài đặt từ bộ nhớ / file", bắt buộc phải thêm qua URL Repository (`repo.json`).
* **Giải pháp:** Xây dựng server máy chủ cục bộ linh hoạt [host_local_repo.py](file:///data/data/com.termux/files/home/Anime47SubtitleEnricher/host_local_repo.py). Server này tự động cấp link HTTPS (qua Cloudflare Tunnel `trycloudflare.com`) kết hợp với link HTTP Localhost/LAN, tự điều hướng `plugins.json` và file `.cs3` theo đúng giao thức gọi đến.

#### 2. Sự cố "Đăng nhập thất bại" (Lỗi HTTP 422 từ NiceHttp)
* **Vấn đề:** Khi gửi `json = payload` (trong đó `payload` là chuỗi JSON), thư viện NiceHttp của CloudStream tự động đặt header `Content-Type: text/plain;charset=utf-8`. Backend Laravel của Anime47 không parse được body, trả về `HTTP 422: Trường login và password không được bỏ trống`.
* **Giải pháp:** Chuyển sang gửi payload dạng `data = mapOf("login" to ..., "password" to ..., "remember" to "true")` chuẩn `application/x-www-form-urlencoded`. Backend phản hồi `HTTP 200 OK` tức thì. Bổ sung cơ chế Regex bóc tách token dự phòng hai lớp.

#### 3. Sự cố Torrentio không hiển thị phụ đề Anime47
* **Vấn đề:** 
  1. Khi xem phim từ Torrentio, chuỗi `data` truyền vào `loadLinks` chỉ là ID (ví dụ: `kitsu:46522:1` hoặc `tt23851532:1:1`), hoàn toàn không chứa tên bộ anime. Tên stream `link.name` chỉ là `[RD+] Torrentio 1080p`. Dẫn đến việc tìm kiếm Anime47 với từ khóa `"kitsu"` hoặc `"Torrentio"` không ra kết quả.
  2. `loadLinks` trả về quá nhanh khiến CloudStream đóng bộ thu nhận phụ đề trước khi tiến trình cào phụ đề ngầm hoàn tất.
* **Giải pháp (Bản v3):**
  1. **Đánh chặn `load(url)`:** Ngay khi người dùng mở trang anime trên Torrentio, hook lưu lại **chính xác 100% tên anime gốc** (`response.name`), cùng toàn bộ tên tiếng Anh, tiếng Nhật, Synonyms và danh sách tập.
  2. **Tìm kiếm đa tầng:** Nếu tìm Anime47 bằng tên chính không thấy, hệ thống tự động thử lần lượt các tên tiếng Anh và Synonyms.
  3. **Bóc tách Magnet & URL file:** Tự động giải mã tham số `&dn=` trên magnet link hoặc tên file `.mkv`/`.mp4` trong luồng phát.
  4. **Giữ luồng đồng bộ:** `loadLinks` chờ tiến trình cào phụ đề tối đa 4.5s để đảm bảo file `.vtt` được nạp vào player trước khi phát video.
  5. **Vòng lặp quan sát liên tục:** Hook liên tục quét cả `APIHolder.allProviders` và `APIHolder.apis` trong suốt phiên chạy của ứng dụng.

* **Kết quả nghiệm thu:** Phụ đề `Tiếng Việt (Anime47)` đã tự động xuất hiện chuẩn xác 100% trong danh sách phụ đề của trình phát CloudStream khi xem anime qua Torrentio!

---

## 3. Hướng dẫn Vận hành & Chạy Server

### 🚀 Cách 1: Khởi chạy Local Repository Server (Dùng ngay trên Termux)
Server này phục vụ cài đặt hoặc cập nhật tiện ích trực tiếp vào CloudStream mà không cần tài khoản GitHub:

1. Chạy lệnh:
   ```bash
   python3 /data/data/com.termux/files/home/Anime47SubtitleEnricher/host_local_repo.py
   ```
2. Màn hình console sẽ in ra các đường link:
   * **Link HTTPS Cloudflare (Khuyên dùng):** `https://xxxx.trycloudflare.com/repo.json` *(100% không bị Android chặn mạng)*.
   * **Link Localhost:** `http://127.0.0.1:8080/repo.json`.
   * **Link Wi-Fi LAN:** `http://192.168.1.x:8080/repo.json` *(dành cho Android TV cùng mạng Wi-Fi)*.
3. Trong **CloudStream**:
   * Vào **Cài đặt** $\rightarrow$ **Tiện ích mở rộng** $\rightarrow$ **Thêm kho lưu trữ**.
   * Nhập link HTTPS ở trên $\rightarrow$ Bấm **Thêm**.
   * Vào danh sách plugin của kho vừa thêm $\rightarrow$ Bấm **Cài đặt** (hoặc **Cập nhật**).

*(Sau khi cài đặt xong vào CloudStream, plugin sẽ được lưu vĩnh viễn trong máy, bạn có thể bấm `Ctrl+C` tắt server trong Termux bất kỳ lúc nào).*

---

### 🔨 Cách 2: Biên dịch lại sau khi chỉnh sửa mã nguồn
Nếu bạn sửa đổi code Kotlin trong thư mục `Anime47Enricher/src/`:

1. Di chuyển vào thư mục dự án và chạy:
   ```bash
   cd /data/data/com.termux/files/home/Anime47SubtitleEnricher
   python3 build_plugin.py
   ```
2. Công cụ sẽ tự động:
   * Biên dịch Kotlin $\rightarrow$ Dex bằng Google D8.
   * Đóng gói lại [Anime47Enricher.cs3](file:///data/data/com.termux/files/home/Anime47SubtitleEnricher/dist/Anime47Enricher.cs3).
   * Cập nhật mã băm SHA256 và sinh lại file `plugins.json` và `repo.json` trong thư mục [dist/](file:///data/data/com.termux/files/home/Anime47SubtitleEnricher/dist).

---

### 🌐 Cách 3: Host Repo cố định qua GitHub (Dùng lâu dài)
Nếu muốn đưa lên GitHub để CloudStream tự động cập nhật mà không cần mở Termux:

1. Tạo 1 repository mới trên GitHub (chọn **Public**), ví dụ: `https://github.com/<username>/Anime47SubtitleEnricher`.
2. Cập nhật lại đường dẫn trong `dist/repo.json` và `dist/plugins.json`:
   ```bash
   python3 /data/data/com.termux/files/home/Anime47SubtitleEnricher/update_repo_url.py https://raw.githubusercontent.com/<username>/Anime47SubtitleEnricher/builds
   ```
3. Đẩy các file trong thư mục `dist/` lên nhánh `builds` của GitHub:
   ```bash
   cd /data/data/com.termux/files/home/Anime47SubtitleEnricher
   git init
   git branch -M main
   git remote add origin https://github.com/<username>/Anime47SubtitleEnricher.git
   git add .
   git commit -m "Release Anime47 Subtitle Enricher"
   git push -u origin main
   ```
4. Trong CloudStream, thêm link kho lưu trữ vĩnh viễn:
   ```text
   https://raw.githubusercontent.com/<username>/Anime47SubtitleEnricher/builds/repo.json
   ```

---

## 4. Cấu trúc Thư mục Dự án

```text
Anime47SubtitleEnricher/
├── Anime47SubtitleEnricher_plan.md   # Tài liệu kỹ thuật & Kế hoạch hoàn chỉnh (File này)
├── build_plugin.py                  # Tool tự động biên dịch Kotlin -> Dex -> .cs3
├── host_local_repo.py               # Máy chủ local đa giao thức phục vụ nạp repo vào CloudStream
├── update_repo_url.py               # Tool cập nhật URL repo & plugins.json nhanh
├── build.gradle.kts                 # Cấu hình Gradle chuẩn CloudStream
├── settings.gradle.kts              # Cấu hình Gradle project
├── gradlew                          # Gradle wrapper script
├── dist/                            # Thư mục chứa sản phẩm xuất xưởng Release
│   ├── Anime47Enricher.cs3          # File binary tiện ích CloudStream (classes.dex + manifest)
│   ├── plugins.json                 # Danh mục plugin kèm mã băm SHA256
│   └── repo.json                    # File nạp kho lưu trữ cho CloudStream
├── Anime47Enricher/
│   └── src/main/kotlin/com/anime47/enricher/
│       ├── Anime47Auth.kt           # Xác thực, quản lý JWT token, lưu SharedPreferences
│       ├── Anime47Hook.kt           # Dynamic Provider Hook đánh chặn load() và loadLinks()
│       ├── Anime47Matcher.kt        # Bóc tách tên anime, tập, thuật toán so khớp Dice Coefficient
│       ├── Anime47Plugin.kt         # Entrypoint plugin của CloudStream & giao diện Settings UI
│       ├── Anime47Provider.kt       # Provider độc lập xem phim Anime47 trên CloudStream
│       ├── Anime47Scraper.kt        # Bộ cào API tìm kiếm, danh sách tập, link phụ đề .vtt
│       └── models/
│           └── Models.kt            # Định nghĩa toàn bộ Data Class
└── .github/workflows/
    └── build.yml                    # CI tự động build trên GitHub Actions
```
