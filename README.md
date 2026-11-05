# CloudStream Subtitle Enrichers Repository 🚀

Kho tiện ích mở rộng (Plugin Repository) chính thức cho [CloudStream](https://github.com/recloudstream/cloudstream), tự động cào và nhúng phụ đề đa ngôn ngữ (Tiếng Việt, Tiếng Anh, Tiếng Nhật...) vào mọi luồng phát phim và anime.

---

## 📲 Cài đặt nhanh vào CloudStream (Installation)

### Cách 1: Thêm trực tiếp vào CloudStream (Khuyên dùng)
1. Mở ứng dụng **CloudStream** trên điện thoại / Android TV.
2. Vào **Cài đặt (Settings)** $\rightarrow$ **Tiện ích mở rộng (Extensions)**.
3. Nhấn **Thêm kho lưu trữ (Add Repository)**.
4. Nhập tên kho lưu trữ: `CloudStream Subtitle Enrichers`
5. Dán đường link sau vào ô URL:
```text
https://raw.githubusercontent.com/Tinnguyen8426/cloudstream-extensions/builds/repo.json
```
6. Nhấn **Tải xuống (Download)** / **Lưu (Save)**.
7. Chọn extension bạn muốn cài đặt và nhấn **Cài đặt (Install)**!

---

## 🧩 Danh sách các Extension (Included Extensions)

### 1. 🌐 Stremio Subtitle Enricher (`StremioSubEnricher.cs3`)
- **Nguồn phụ đề:** Tự động truy vấn song song hệ sinh thái Stremio bao gồm **Kitsu**, **Cinemeta**, **OpenSubtitles v3** và các Addon Stremio tùy chỉnh.
- **Tính năng:**
  - Hỗ trợ đầy đủ cho Anime, Phim điện ảnh (Movies) và Phim bộ (TV Series).
  - Từ điển 34 mã ngôn ngữ chuẩn ISO 639-1 với cài đặt lọc ngôn ngữ ưu tiên.
  - Bộ đệm LRU Cache thông minh, giảm tải truy vấn mạng.
  - Thuật toán fuzzy matching chuẩn hóa Unicode NFC và xử lý CJK.

### 2. 🦊 Kitsunekko Japanese Subtitle Enricher (`KitsunekkoEnricher.cs3`)
- **Nguồn phụ đề:** Kho phụ đề tiếng Nhật lớn nhất cho Anime từ [kitsunekko.net](https://kitsunekko.net).
- **Tính năng:**
  - Hỗ trợ định dạng `.ass`, `.srt` và **giải nén trực tiếp file nén `.zip` dạng streaming** qua `ZipInputStream` (không tốn dung lượng ổ đĩa).
  - Tự động nhận diện bảng mã tiếng Nhật: **Shift-JIS**, **UTF-8**, **Windows-1252**.
  - Bảo vệ an toàn chống lỗ hổng ZipSlip.
  - Thuật toán đối sánh nhận diện mùa (Season), chữ số La Mã và phạt điểm khi sai season.

### 3. 🇻🇳 Anime47 Subtitle Enricher (`Anime47Enricher.cs3`)
- **Nguồn phụ đề:** Nguồn Anime Vietsub [Anime47](https://anime47.best).
- **Tính năng:**
  - Tự động trích xuất phụ đề tiếng Việt (softsub) chuẩn ASS/SRT.
  - Tương thích hoàn hảo với các nguồn phát P2P như Torrentio, TorraStream, StremioX, AnimePahe...

---

## ⚡ Kiến trúc & Khả năng tương thích đồng thời (Multi-Enricher Coexistence)

Cả 3 extension đều tuân thủ kiến trúc **Non-Blocking Decorator Chain**:
```text
ExoPlayer / Trình phát
     │
     ▼
[KitsunekkoEnrichedMainAPI]   --> Phụ đề tiếng Nhật (kitsunekko.net)
     │
     ▼
[StremioEnrichedMainAPI]      --> Phụ đề đa ngôn ngữ (Kitsu / Cinemeta / OpenSubtitles)
     │
     ▼
[Anime47Hook.EnrichedMainAPI] --> Phụ đề tiếng Việt (Anime47)
     │
     ▼
[Base Provider (Torrentio, AnimePahe...)] --> Trả link video với 0ms độ trễ
```

- **0ms Video Passthrough Latency:** Link phát video được trả về trình phát ngay tức thì, không bị giữ lại bởi tiến trình cào phụ đề.
- **Giới hạn Timeout 4.5s:** Quá trình tải phụ đề chạy song song ngầm và được giới hạn tối đa 4.5 giây, đảm bảo trải nghiệm xem phim không bao giờ bị nghẽn mạng hay giật lag.
- **Hoạt động độc lập & Đồng thời:** Cả 3 enricher có thể cài đặt độc lập hoặc kích hoạt cùng lúc, tự động gộp các track phụ đề vào cùng một menu trong trình phát CloudStream.

---

## 🛠️ Biên dịch từ mã nguồn (Build from Source)

Yêu cầu: Python 3.8+ và Java JDK 17+.

```bash
# Biên dịch Stremio Subtitle Enricher
python StremioSubtitleEnricher/build_plugin.py

# Biên dịch Kitsunekko Japanese Subtitle Enricher
python KitsunekkoSubtitleEnricher/build_plugin.py

# Tạo metadata và kho lưu trữ cho toàn bộ repo
python make_repo.py
```
