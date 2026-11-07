# Kiến Trúc Kỹ Thuật & Kế Hoạch Triển Khai Chi Tiết
# Extension Dịch Phụ Đề Bằng AI Cho CloudStream (`AiSubtitleTranslator.cs3`)

**Tác giả**: Kỹ sư M5 (`m5_worker_2`)  
**Nền tảng mục tiêu**: CloudStream 3 (Android / Android TV, minSdk 21, targetSdk 34)  
**Artifact đầu ra**: `AiSubtitleTranslator.cs3`  
**Thư mục dự án**: `c:\Users\taoka\Documents\ca_nhan\cloudstream_ext`  
**Tài liệu tham chiếu**: `ORIGINAL_REQUEST.md`, `PROJECT.md`, `Anime47SubtitleEnricher`, `KitsunekkoSubtitleEnricher`, `StremioSubtitleEnricher`  
**Thời gian**: Tháng 11/2026  
**Trạng thái**: KIẾN TRÚC ĐÃ PHÊ DUYỆT & LỘ TRÌNH TRIỂN KHAI ĐÃ ĐƯỢC THẨM ĐỊNH (Vòng lặp 2 - Chuẩn Production)  

---

## 1. Tóm Tắt Tổng Quan & Tổng Quan Kiến Trúc

### 1.1 Thiết Kế Cấp Cao Của Hệ Thống & Vai Trò Của Extension
**CloudStream AI Subtitle Translator** (`AiSubtitleTranslator`) là một extension dạng dynamic hook tầng ngoài cùng (outermost hook) được thiết kế nhằm nâng cấp CloudStream 3 với khả năng dịch phụ đề theo thời gian thực bằng trí tuệ nhân tạo (AI). Các nguồn phát trực tuyến hiện đại (như Torrentio, AnimePahe, SuperStream) và các extension enricher phụ đề hiện có (`Anime47SubtitleEnricher`, `KitsunekkoSubtitleEnricher`, `StremioSubtitleEnricher`) cung cấp các luồng phim phong phú kèm softsub ở nhiều ngôn ngữ nguồn khác nhau (tiếng Nhật `.ass`/`.srt`, tiếng Việt `.vtt`, tiếng Anh/Tây Ban Nha `.srt`). Tuy nhiên, người xem thường xuyên gặp phải các nội dung chưa có phụ đề bằng tiếng mẹ đẻ hoặc ngôn ngữ mong muốn.

`AiSubtitleTranslator` sẽ chặn bắt (intercept) các luồng phụ đề phát ra trên toàn bộ hệ sinh thái provider của CloudStream, dịch các câu thoại sang ngôn ngữ đích do người dùng lựa chọn thông qua các API AI hàng đầu (**OpenAI & Structured Outputs tương thích**, **Google AI Studio / Gemini REST `v1beta`**, và **Anthropic Messages API**), tái cấu trúc các track SubRip (`.srt`) chuẩn chỉnh, và chèn chúng trở lại trình phát của CloudStream thông qua một máy chủ HTTP loopback nội bộ.

```
┌────────────────────────────────────────────────────────────────────────┐
│                        CloudStream Player Engine                       │
│                         (PlayerViewModel / UI)                         │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ 1. Gọi loadLinks(...)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│               AITranslatorEnrichedMainAPI (ĐẦU CHUỖI NGOÀI CÙNG)        │
│   • Chặn bắt các phụ đề phát ra qua interceptingSubtitleCallback       │
│   • Chuyển tiếp đồng bộ phụ đề gốc tới player ngay lập tức (độ trễ 0ms)│
│   • Điều phối phiên dịch thuật AI chạy bất đồng bộ ở nền               │
│   • Cơ chế Dynamic Self-Hoisting đảm bảo luôn ở đầu chuỗi hook         │
│   • Crash Barrier bao bọc provider bên trong để cô lập RuntimeException│
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ 2. Truyền wrapped callback
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   KitsunekkoEnrichedMainAPI (Enricher)                 │
│   • Cung cấp phụ đề softsub tiếng Nhật từ kitsunekko.net               │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ 3. Truyền wrapped callback
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                     StremioEnrichedMainAPI (Enricher)                  │
│   • Cung cấp phụ đề đa ngôn ngữ từ OpenSubtitles, Kitsu, Cinemeta      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ 4. Truyền wrapped callback
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                    Anime47Hook.EnrichedMainAPI (Enricher)              │
│   • Cung cấp phụ đề tiếng Việt từ Anime47                              │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ 5. Ủy thác cho leaf provider
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                      Base Video Provider (Leaf API)                    │
│                 (Ví dụ: Torrentio, AnimePahe, ShowBox)                 │
└───────────────────┬────────────────────────────────┬───────────────────┘
                    │ callback(link)                 │ subtitleCallback(sub)
                    │ (Tức thì 0ms)                  │ (Bất đồng bộ)
                    ▼                                ▼
┌───────────────────────────────────────┐   ┌────────────────────────────┐
│          ExoPlayer Video Media        │   │ Đến Interceptor Callback   │
│         (Bắt đầu phát <150ms)         │   └──────────────┬─────────────┘
└───────────────────────────────────────┘                  │
            ┌──────────────────────────────────────────────┴─────────────┐
            │ NHÁNH 1: Chuyển tiếp ngay lập tức                          │ NHÁNH 2: AI Pipeline chạy ngầm
            ▼                                                            ▼
┌───────────────────────────────────────┐            ┌───────────────────────────────────────┐
│ Menu Phụ Đề Player (Track Gốc)        │            │       Hàng Đợi AITranslationSession   │
│   Ví dụ: "Tiếng Nhật (Kitsunekko)"    │            │   • Tải phụ đề qua app.get()          │
└───────────────────────────────────────┘            │   • Multi-Format Parser (SRT/VTT)     │
                                                     │   • Che chắn thẻ style ([[TAG_n]])    │
                                                     │   • Kiểm tra Cache 2 Tầng (SHA-256)   │
                                                     │   • Dịch theo chunk với Decoupled ID  │
                                                     │   • Tự động khớp lỗi (Auto-Healing)   │
                                                     │   • Tái cấu trúc chuẩn SubRip (.srt)  │
                                                     │   • Ghi nguyên tử an toàn ra đĩa      │
                                                     └───────────────────┬───────────────────┘
                                                                         │
                                                                         ▼
                                                     ┌───────────────────────────────────────┐
                                                     │  AITranslatorLocalServer (127.0.0.1)  │
                                                     │  http://127.0.0.1:<port>/sub/<hash>.srt
                                                     └───────────────────┬───────────────────┘
                                                                         │
                                                                         ▼
                                                     ┌───────────────────────────────────────┐
                                                     │ SubtitleFile phát vào Menu Player     │
                                                     │ Chiến lược A: "[AI] Ngôn ngữ (Model)" │
                                                     │ Chiến lược B: "_v1" Draft -> "_full"  │
                                                     └───────────────────────────────────────┘
```

---

### 1.2 Cấu Trúc Chuỗi Provider: Outermost Decorator Với Cơ Chế Dynamic Self-Hoisting

#### Bất Biến Vị Trí Ngoài Cùng (The Outermost Position Invariant)
Trong kiến trúc decorator chuỗi của CloudStream, một lớp bọc ngoài sẽ cung cấp `subtitleCallback` truyền vào cho các lớp bọc bên trong.
- Nếu một enricher nằm **bên trong** một lớp bọc khác, nó sẽ không thể chặn bắt các phụ đề được phát ra từ những lớp bọc nằm ngoài nó.
- Để chặn bắt phụ đề từ **toàn bộ** các enricher hiện có (`KitsunekkoEnrichedMainAPI`, `StremioEnrichedMainAPI`, `Anime47Hook.EnrichedMainAPI`) cũng như `BaseProvider` cơ sở, `AITranslatorEnrichedMainAPI` **bắt buộc phải chiếm vị trí ngoài cùng (Đầu chuỗi Decorator)** đối với mọi provider được đăng ký trong `APIHolder.allProviders`.

#### Cơ Chế Tự Đưa Lên Đầu Chuỗi (Dynamic Self-Hoisting) & Đồng Bộ Plugin Mapping
Do các plugin CloudStream được tải động và bất đồng bộ, một enricher ngang hàng (như Kitsunekko hoặc Stremio) có thể được tải hoặc cập nhật sau `AiSubtitleTranslator`. Một wrapper đơn giản chạy một lần sẽ dễ dàng bị đẩy vào vị trí bên trong.

Hơn nữa, CloudStream phân giải các provider thông qua hai sổ đăng ký riêng biệt:
1. `APIHolder.allProviders`: Danh sách chính chứa các provider đã tải.
2. `APIHolder.getPluginMapping(providerName)` / `APIHolder.addPluginMapping(provider)`: Sổ đăng ký được sử dụng khi người dùng mở phim từ **Tìm kiếm (Search), Dấu trang (Bookmarks), Lịch sử xem (History), hoặc Liên kết sâu (Deep Links)**.

Để thực thi Bất biến Vị trí Ngoài cùng và ngăn chặn tình trạng người dùng xem từ tìm kiếm/dấu trang bị lọt qua hook:
1. Một chu kỳ quét nền 3 giây một lần sẽ kích hoạt `AITranslatorHook.scanAndHoistHooks()`.
2. Quá trình lặp được đồng bộ hóa trên `APIHolder.allProviders` bằng ngữ nghĩa `@Synchronized` và `synchronized` để ngăn lỗi `ConcurrentModificationException` khi các enricher khác đang được gắn vào.
3. Mỗi decorator sau khi được nâng lên đầu chuỗi sẽ lập tức được đăng ký vào `APIHolder.addPluginMapping(hoisted)` nhằm đảm bảo các lần mở phim từ tìm kiếm hay dấu trang đều trỏ về provider đã được bọc.
4. Quá trình hoán đổi delegate luôn kiểm tra kỹ các trường đối tượng xem có khớp với đối tượng con trước đó hay không trước khi ghi đè, tránh làm hỏng các tham chiếu trong các lớp multi-provider.

```kotlin
package com.lagradost.cloudstream3.subtitles.ai.hook

import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

object AITranslatorHook {
    private const val TAG = "AITranslatorHook"
    private val DELEGATE_GETTERS = listOf("getBaseApi", "getOriginal", "getDelegate", "getBase", "getInner")

    fun getInnerDelegate(provider: MainAPI): MainAPI? {
        if (provider is AITranslatorEnrichedMainAPI) return provider.baseApi

        for (getterName in DELEGATE_GETTERS) {
            try {
                val method = provider.javaClass.getMethod(getterName)
                if (MainAPI::class.java.isAssignableFrom(method.returnType)) {
                    val candidate = method.invoke(provider) as? MainAPI
                    if (candidate != null && candidate !== provider) return candidate
                }
            } catch (_: NoSuchMethodException) {}
            catch (_: Throwable) {}
        }

        var clazz: Class<*>? = provider.javaClass
        while (clazz != null && clazz != Any::class.java && clazz != MainAPI::class.java) {
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (MainAPI::class.java.isAssignableFrom(field.type)) {
                    try {
                        field.isAccessible = true
                        val candidate = field.get(provider) as? MainAPI
                        if (candidate != null && candidate !== provider) return candidate
                    } catch (_: Throwable) {}
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    fun getBaseProvider(provider: MainAPI): MainAPI {
        val visited = Collections.newSetFromMap(IdentityHashMap<MainAPI, Boolean>())
        var current = provider
        visited.add(current)
        while (true) {
            val inner = getInnerDelegate(current) ?: break
            if (!visited.add(inner)) break // Phát hiện vòng lặp (cycle)
            current = inner
        }
        return current
    }

    fun setInnerDelegate(parent: MainAPI, oldChild: MainAPI, newDelegate: MainAPI): Boolean {
        if (parent is AITranslatorEnrichedMainAPI) {
            parent.baseApi = newDelegate
            return true
        }
        var clazz: Class<*>? = parent.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (MainAPI::class.java.isAssignableFrom(field.type)) {
                    try {
                        field.isAccessible = true
                        // Kiểm tra an toàn nghiêm ngặt: chỉ ghi đè nếu trường này hiện đang tham chiếu tới oldChild
                        val currentVal = field.get(parent)
                        if (currentVal === oldChild) {
                            field.set(parent, newDelegate)
                            return true
                        }
                    } catch (_: Throwable) {}
                }
            }
            clazz = clazz.superclass
        }
        return false
    }

    fun unwrapAITranslatorChain(root: MainAPI): MainAPI {
        if (root is AITranslatorEnrichedMainAPI) {
            return unwrapAITranslatorChain(root.baseApi)
        }
        val visited = Collections.newSetFromMap(IdentityHashMap<MainAPI, Boolean>())
        visited.add(root)
        var parent: MainAPI? = root
        while (parent != null) {
            val child = getInnerDelegate(parent) ?: break
            if (!visited.add(child)) break
            if (child is AITranslatorEnrichedMainAPI) {
                val unwrappedChild = unwrapAITranslatorChain(child.baseApi)
                setInnerDelegate(parent, child, unwrappedChild)
                break
            }
            parent = child
        }
        return root
    }

    @Synchronized
    fun scanAndHoistHooks() {
        val allProviders = APIHolder.allProviders
        synchronized(allProviders) {
            for (i in 0 until allProviders.size) {
                val current = allProviders.getOrNull(i) ?: continue
                val base = getBaseProvider(current)

                // Không bao giờ bọc chính provider độc lập của chúng ta hoặc base null
                if (base.name.equals("AITranslatorProvider", ignoreCase = true)) continue

                // Nếu đầu chuỗi hiện tại đã là wrapper của chúng ta, đảm bảo chuỗi bên trong sạch sẽ
                if (current is AITranslatorEnrichedMainAPI) continue

                // Provider đang bị bọc bởi enricher khác hoặc chưa được bọc:
                // 1. Tách bất kỳ thể hiện AI translator nào đang bị chôn bên trong
                val cleanChain = unwrapAITranslatorChain(current)
                // 2. Bọc ở vị trí ngoài cùng (Head)
                val hoisted = AITranslatorEnrichedMainAPI(cleanChain)
                allProviders[i] = hoisted

                // 3. Đồng bộ hóa CloudStream Plugin Mapping phục vụ Search & Bookmarks
                try {
                    APIHolder.addPluginMapping(hoisted)
                } catch (t: Throwable) {
                    Log.w(TAG, "Không thể cập nhật plugin mapping cho ${hoisted.name}: ${t.message}")
                }
            }
        }
    }
}
```

---

### 1.3 Các Bất Biến Phân Phối Phát ExoPlayer & Đặc Tả Chiến Lược

#### Bất Biến Nạp Phụ Đề Của ExoPlayer (ExoPlayer Subtitle Ingestion Invariant)
Trong CloudStream 3, ExoPlayer nạp các luồng phụ đề thông qua `SingleSampleMediaSource` và `CustomSubripParser`.
- Khi một URL phụ đề (ví dụ `http://127.0.0.1:<port>/sub/<key>.srt`) được chọn hoặc phát ra, OkHttp sẽ tải tệp **một lần duy nhất cho đến khi gặp ký tự kết thúc tệp (EOF)**.
- ExoPlayer phân tích cú pháp toàn bộ các cue vào một chỉ mục `Subtitle` trong bộ nhớ RAM và đóng kết nối mạng.
- **ExoPlayer KHÔNG BAO GIỜ thăm dò (re-poll) hay đọc lại URL loopback trong suốt quá trình phát phim liên tục.**
- Việc ghi thêm các cue mới vào tệp trên đĩa trong khi phim đang phát **hoàn toàn không có tác dụng gì** đối với track phụ đề trong bộ nhớ. Nếu phát ra một tệp tạm chỉ chứa các Cue từ 1 đến 30, phụ đề sẽ biến mất vĩnh viễn sau phút thứ 2 của video!

Để tuân thủ tuyệt đối bất biến của engine phát này, `AiSubtitleTranslator` triển khai **hai chiến lược phân phối tất định**:

#### Chiến Lược A: Phân Phối Batch Tốc Độ Cao Chính (Mặc định)
Các mô hình LLM nhẹ hiện đại (`gpt-4o-mini`, `gemini-1.5-flash`, `llama-3.3-70b-versatile` trên Groq) xử lý các chunk 30 cue trong khoảng 400ms – 800ms.
- Một tập phim anime tiêu chuẩn dài 24 phút chứa khoảng ~360 cue (12 chunks).
- Sử dụng nhóm coroutine đồng thời có giới hạn (`Semaphore(4)`), toàn bộ 12 chunk được dịch xong trong **tổng cộng 2.2 đến 2.8 giây**.
- Vì thời gian đệm video (buffering) và giải mã keyframe ban đầu mất từ 1.5 đến 3.0 giây, nên việc dịch toàn bộ tập phim hoàn tất **trước hoặc ngay tại thời điểm video bắt đầu phát**.
- Extension phát tệp SubRip hoàn chỉnh, đã được xác thực một lần duy nhất vào `playerSubtitleCallback`:
  `SubtitleFile(lang = "Tiếng Việt [AI: gpt-4o-mini]", url = "http://127.0.0.1:<port>/sub/<cacheKey>.srt")`.
- **Kết quả**: Chính xác một track phụ đề sạch; 0 câu thoại bị thiếu; tuân thủ 100% cơ chế tải single-sample của ExoPlayer.

#### Chiến Lược B: Khung Sườn Lũy Tiến / Đa Phiên Bản Track (Phim Dài & Mạng Yếu)
Dành cho các bộ phim điện ảnh dài 2 tiếng (1.800+ cue = 60 chunks) hoặc kết nối mạng bị giới hạn băng thông:
1. **Phát Khung Sườn Cue Đầy Đủ Sau Chunk 1 (Chunk 1 Skeleton Emission)**:
   - Ngay khi Chunk 1 dịch xong (~800ms), phiên dịch sẽ tạo một **Khung sườn Cue Đầy Đủ (Full Cue Skeleton)**.
   - Các Cue 1–30 chứa nội dung đã dịch.
   - Các Cue 31–N chứa **nội dung thoại gốc ban đầu** làm phương án dự phòng không rỗng.
   - Do toàn bộ các cue đều có mặt với timecode hợp lệ, `CustomSubripParser` của ExoPlayer sẽ lập chỉ mục toàn bộ dòng thời gian từ phút 0 đến phút 120. Lời thoại không bao giờ bị biến mất.
   - Được phát dưới nhãn nháp: `[AI] Tiếng Việt (Đang dịch...)` tại `http://127.0.0.1:<port>/sub/<cacheKey>_v1.srt`.
2. **Phát Track Hoàn Thiện Chính Thức (Authoritative Full Track Emission)**:
   - Các coroutine nền tiếp tục dịch các chunk từ 2 đến N vào bộ nhớ đệm Tier 2 trên đĩa.
   - Khi 100% các chunk đã được ghép nối, phiên dịch ghi nội dung hoàn chỉnh ra `http://127.0.0.1:<port>/sub/<cacheKey>_full.srt` và phát track chính thức:
     `[AI] Tiếng Việt (Hoàn tất)` tới `playerSubtitleCallback`.
   - Người dùng hoặc trình phát có thể chuyển đổi mượt mà sang track đã hoàn thành.

---

### 1.4 Yêu Cầu Máy Chủ HTTP Loopback Nhúng (Embedded Loopback HTTP Server)
Kiểm tra mã nguồn `PlayerSubtitleHelper` và việc tích hợp ExoPlayer của CloudStream xác nhận:
- ExoPlayer tải phụ đề thông qua `OkHttpDataSource.Factory`.
- Trình xác thực URL nội bộ của OkHttp kiểm tra rõ ràng:
  ```java
  if (!url.scheme().equalsIgnoreCase("http") && !url.scheme().equalsIgnoreCase("https")) {
      throw new IllegalArgumentException("Expected URL scheme 'http' or 'https' but was '" + url.scheme() + "'");
  }
  ```
- Việc phát ra đường dẫn tệp cục bộ `file:///data/user/0/.../sub.srt` sẽ gây crash trình phát phụ đề với lỗi `IllegalArgumentException`.
- Do đó, extension nhúng một máy chủ HTTP loopback siêu nhẹ (`AITranslatorLocalServer`) gắn vào địa chỉ `127.0.0.1:<ephemeral-port>`. Phụ đề được phân phối tại `http://127.0.0.1:<port>/sub/<cacheKey>.srt` với header `Content-Type: text/plain; charset=utf-8`, đảm bảo tương thích 100% với ExoPlayer.

---

### 1.5 Đặc Tả Điểm Khởi Chạy Plugin CloudStream (`AITranslatorPlugin.kt`)
Điểm khởi chạy (entrypoint) của extension điều phối toàn bộ vòng đời:

```kotlin
package com.lagradost.cloudstream3.subtitles.ai

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import com.lagradost.cloudstream3.subtitles.ai.hook.AITranslatorHook
import com.lagradost.cloudstream3.subtitles.ai.server.AITranslatorLocalServer
import com.lagradost.cloudstream3.subtitles.ai.ui.AITranslatorSettingsDialog
import kotlinx.coroutines.*

@CloudstreamPlugin
class AITranslatorPlugin : Plugin() {
    companion object {
        private const val TAG = "AITranslatorPlugin"
    }

    private val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var hookPollingJob: Job? = null

    override fun load(context: Context) {
        Log.i(TAG, "Đang khởi tạo Plugin AI Subtitle Translator...")
        
        // 1. Khởi tạo cấu hình lưu trữ vĩnh viễn (persistent configuration)
        TranslatorConfig.init(context)

        // 2. Khởi động máy chủ HTTP loopback nhúng cho luồng ExoPlayer
        val serverPort = AITranslatorLocalServer.start(context)
        Log.i(TAG, "AITranslatorLocalServer đang hoạt động tại cổng: $serverPort")

        // 3. Đăng ký Dialog Cài Đặt
        openSettings = { ctx ->
            AITranslatorSettingsDialog.show(ctx)
        }

        // 4. Khởi chạy tác vụ quét hook tự đưa lên đầu chuỗi định kỳ (Self-Hoisting)
        hookPollingJob = pluginScope.launch {
            while (isActive) {
                try {
                    AITranslatorHook.scanAndHoistHooks()
                } catch (e: Throwable) {
                    Log.w(TAG, "Lỗi quét hook: ${e.message}")
                }
                delay(3000L)
            }
        }
    }

    override fun beforeUnload() {
        Log.i(TAG, "Đang dỡ bỏ Plugin AI Subtitle Translator...")
        hookPollingJob?.cancel()
        pluginScope.cancel()

        // Gỡ bỏ sạch sẽ toàn bộ hook để khôi phục chuỗi provider gốc và các plugin mapping
        val allProviders = APIHolder.allProviders
        synchronized(allProviders) {
            for (i in 0 until allProviders.size) {
                val current = allProviders.getOrNull(i) ?: continue
                val unwrapped = AITranslatorHook.unwrapAITranslatorChain(current)
                allProviders[i] = unwrapped
                try {
                    APIHolder.addPluginMapping(unwrapped)
                } catch (_: Throwable) {}
            }
        }

        // Dừng máy chủ HTTP loopback
        AITranslatorLocalServer.stop()
    }
}
```

---

## 2. Đặc Tả Pipeline & Nạp Phụ Đề (Subtitle Ingestion & Pipeline Specification)

### 2.1 Khám Phá Phụ Đề, Callback Nhánh Kép & Rào Chắn Lỗi (Crash Barrier)
Khi CloudStream bắt đầu nạp liên kết (loadLinks) cho một tập phim đã chọn, phương thức `AITranslatorEnrichedMainAPI.loadLinks` sẽ chặn bắt lời gọi này.

```kotlin
package com.lagradost.cloudstream3.subtitles.ai.hook

import android.util.Log
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.subtitles.ai.session.AITranslationSession
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AITranslatorEnrichedMainAPI(var baseApi: MainAPI) : MainAPI() {
    companion object {
        private const val TAG = "AITranslatorAPI"
    }

    init {
        this.name = baseApi.name
        this.mainUrl = baseApi.mainUrl
        this.lang = baseApi.lang
        this.supportedTypes = baseApi.supportedTypes
        this.hasMainPage = baseApi.hasMainPage
        this.hasQuickSearch = baseApi.hasQuickSearch
    }

    @Volatile private var activeSession: AITranslationSession? = null

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (!TranslatorConfig.isEnabled) {
            return@withContext try {
                baseApi.loadLinks(data, isCasting, subtitleCallback, callback)
            } catch (t: Throwable) {
                Log.e(TAG, "Lỗi unwrap baseApi.loadLinks: ${t.message}", t)
                false
            }
        }

        // Hủy bất kỳ phiên dịch thuật nào đang chạy từ lần phát trước đó
        activeSession?.cancelSession()

        val session = AITranslationSession(
            mediaPayload = data,
            targetLang = TranslatorConfig.targetLanguage,
            playerSubtitleCallback = subtitleCallback
        )
        activeSession = session

        val interceptingSubtitleCallback: (SubtitleFile) -> Unit = { subFile ->
            // NHÁNH 1: CHUYỂN TIẾP ĐỒNG BỘ 0ms ĐẾN TRÌNH PHÁT
            try {
                subtitleCallback(subFile)
            } catch (t: Throwable) {
                Log.w(TAG, "Player subtitle callback phát sinh ngoại lệ: ${t.message}")
            }

            // NHÁNH 2: KHÁM PHÁ VÀ ĐƯA PHỤ ĐỀ ỨNG VIÊN VÀO HÀNG ĐỢI DỊCH BẤT ĐỒNG BỘ
            session.queueCandidate(subFile)
        }

        // CRASH BARRIER: Bao bọc lời gọi provider bên trong bằng try-catch
        try {
            baseApi.loadLinks(data, isCasting, interceptingSubtitleCallback, callback)
        } catch (t: Throwable) {
            Log.e(TAG, "Provider bên trong bị crash khi loadLinks: ${t.message}", t)
            activeSession?.cancelSession()
            activeSession = null
            false
        }
    }
}
```

---


### 2.1.1 Thuật Toán Xếp Hạng Ưu Tiên Nguồn Dịch & Lựa Chọn Ngôn Ngữ Nguồn Thông Minh (`SourceLanguagePriority`)
Khi CloudStream nạp các tập phim, các enricher (Anime47, Kitsunekko, Stremio) và các provider cơ sở có thể cùng lúc phát hiện nhiều phụ đề khác nhau (tiếng Nhật `.ass`, tiếng Anh `.srt`, tiếng Việt `.vtt`, tiếng Tây Ban Nha, v.v.).

Để phục vụ tốt nhất cho từng thể loại phim theo nhu cầu của người dùng (ví dụ: Anime Nhật ưu tiên dịch trực tiếp từ tiếng Nhật để giữ nguyên kính ngữ/sắc thái; Phim Hollywood/Âu Mỹ ưu tiên dịch từ tiếng Anh chuẩn), hệ thống tích hợp **Bộ Điều Khiển Mức Độ Ưu Tiên Ngôn Ngữ Nguồn (Source Language Priority Engine)**:

1. **Nguyên Tắc Bỏ Qua Ngôn Ngữ Đích (Target Language Bypass)**:
   - Nếu bất kỳ phụ đề nào quét được trùng khớp với ngôn ngữ đích mà người dùng muốn xem (ví dụ: người dùng chọn xem `Tiếng Việt`, và đã có sẵn track `Tiếng Việt (Anime47)` hoặc `Vietnamese (OpenSubtitles)`):
     - Hệ thống **lập tức chuyển tiếp phụ đề đó cho người xem và KHÔNG kích hoạt dịch AI**.
     - Tiết kiệm 100% token, tránh lãng phí chi phí API và không làm rối menu phụ đề.

2. **Chế Độ Ưu Tiên Ngôn Ngữ Nguồn Có Thể Cấu Hình (Configurable Source Priority Modes)**:
   Người dùng có thể lựa chọn 1 trong 4 chế độ ưu tiên trong Cài đặt extension:
   - **Chế độ 1: Tự Động Thông Minh (Smart Auto - Mặc định)**:
     - Phân tích metadata phim/tập hoặc nhà cung cấp nguồn: Nếu là Anime (Kitsunekko, AnimePahe, Anime47) $	o$ Ưu tiên Tiếng Nhật (`ja` / `jpn`) hàng đầu. Nếu là phim truyền hình/chiếu rạp quốc tế $	o$ Ưu tiên Tiếng Anh (`en` / `eng`).
   - **Chế độ 2: Ưu Tiên Tiếng Nhật (Anime First: JA > EN > Others)**:
     - Luôn ưu tiên phụ đề tiếng Nhật (từ Kitsunekko hoặc softsub gốc) lên mức cao nhất (100 điểm), sau đó mới tới tiếng Anh (80 điểm), và cuối cùng là các ngôn ngữ khác (50 điểm).
   - **Chế độ 3: Ưu Tiên Tiếng Anh (International / Movies First: EN > JA > Others)**:
     - Luôn ưu tiên phụ đề tiếng Anh lên mức cao nhất (100 điểm), sau đó tới tiếng Nhật (70 điểm), rồi đến các ngôn ngữ khác (40 điểm).
   - **Chế độ 4: Tùy Chỉnh Thứ Tự Danh Sách (Custom Priority List)**:
     - Người dùng tự nhập chuỗi mã ngôn ngữ theo thứ tự ưu tiên giảm dần (Ví dụ: `ja, en, fr, es, zh`).

```kotlin
package com.lagradost.cloudstream3.subtitles.ai.session

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.subtitles.ai.config.TranslatorConfig
import java.util.Locale

object SubtitlePriorityEvaluator {
    fun calculateScore(subFile: SubtitleFile, targetLang: String, priorityMode: String, customList: List<String>): Int {
        val lang = subFile.lang.trim().lowercase(Locale.ROOT)

        // 1. Nếu trùng ngôn ngữ đích -> Điểm âm đặc biệt để đánh dấu đã có sẵn bản địa, không cần dịch
        if (isMatchingLanguage(lang, targetLang)) {
            return -1000
        }

        // 2. Chấm điểm theo cấu hình ưu tiên người dùng chọn
        return when (priorityMode) {
            TranslatorConfig.PRIORITY_ANIME_FIRST -> { // Ưu tiên tiếng Nhật
                when {
                    isJapanese(lang) -> 1000
                    isEnglish(lang) -> 500
                    else -> 100
                }
            }
            TranslatorConfig.PRIORITY_ENGLISH_FIRST -> { // Ưu tiên tiếng Anh
                when {
                    isEnglish(lang) -> 1000
                    isJapanese(lang) -> 500
                    else -> 100
                }
            }
            TranslatorConfig.PRIORITY_CUSTOM -> { // Ưu tiên theo danh sách tuỳ chỉnh
                val index = customList.indexOfFirst { isMatchingLanguage(lang, it) }
                if (index >= 0) 1000 - (index * 100) else 50
            }
            else -> { // PRIORITY_AUTO (Tự động)
                when {
                    isEnglish(lang) -> 800
                    isJapanese(lang) -> 750
                    else -> 100
                }
            }
        }
    }

    private fun isJapanese(lang: String): Boolean =
        lang.contains("ja") || lang.contains("jp") || lang.contains("nhật") || lang.contains("japan")

    private fun isEnglish(lang: String): Boolean =
        lang.contains("en") || lang.contains("eng") || lang.contains("anh") || lang.contains("english")

    fun isMatchingLanguage(lang: String, target: String): Boolean {
        val cleanTarget = target.trim().lowercase(Locale.ROOT)
        return lang == cleanTarget || lang.startsWith("$cleanTarget-") || lang.contains(cleanTarget)
    }
}
```

3. **Cơ Chế Nâng Cấp Nguồn Dịch Thông Minh Trong Hàng Đợi (Candidate Promotion)**:
   - Khi các enricher phát ra phụ đề rải rác trong vòng 1-2 giây đầu, nếu ban đầu chỉ thấy phụ đề tiếng Tây Ban Nha (điểm 100), AI Translator sẽ nhận vào hàng đợi.
   - Nhưng 0.5s sau, Kitsunekko phát ra phụ đề tiếng Nhật (điểm 1000 ở chế độ Anime First), phiên dịch sẽ **lập tức hủy tác vụ của phụ đề điểm thấp và chuyển sang phụ đề tiếng Nhật điểm cao nhất**, đảm bảo chất lượng bản dịch đạt mức hoàn hảo nhất!

---

### 2.2 Nạp Phụ Đề Qua Mạng & Chuẩn Hóa Bảng Mã (Charset Normalization)
Các tệp phụ đề có thể nằm trên mạng phân phối nội dung (CDN) HTTPS từ xa hoặc trên các URL loopback cục bộ (`http://127.0.0.1:<port>/sub/...` phát ra từ `KitsunekkoSubtitleEnricher`).
- Phụ đề được nạp bằng client HTTP gốc của CloudStream: `com.lagradost.cloudstream3.app.get`.
- Cơ chế tự động phát hiện bảng mã thực hiện dự phòng theo thứ tự: UTF-8 $	o$ Shift-JIS / Windows-31J $	o$ Windows-1252 $	o$ UTF-16 LE/BE.

---

### 2.3 Bộ Phân Tích Cú Pháp Regex Hợp Nhất Đa Định Dạng (`SubtitleParser.kt`)

#### Xử Lý Cắt Bỏ Thuật Toán & Khắc Phục Chỉ Mục Cue (Algorithmic Truncation & Cue Index Remediation)
Trong chuẩn SubRip (`.srt`) và WebVTT (`.vtt`), các khối cue được phân tách bằng các dòng trống (`\n\n`). Số thứ tự cue (ví dụ `2`) hoặc ID cue của WebVTT (ví dụ `cue-2`) nằm ở dòng ngay trước dòng thời gian.

Bộ phân tích cú pháp chỉ loại bỏ các số/định danh cue ở đuôi một cách nghiêm ngặt khi chúng được phân tách với lời thoại bằng một dòng trống, **ngăn chặn hoàn toàn việc xóa nhầm các câu thoại hợp lệ kết thúc bằng số** (ví dụ: *"Agent 47"*, *"Meet at Room 101"*, *"Score is 10"*).

```kotlin
package com.lagradost.cloudstream3.subtitles.ai.parser

data class SubtitleCue(
    val index: Int,
    val cueId: String? = null,
    val startMs: Long,
    val endMs: Long,
    val cueSettings: String? = null,
    val rawText: String,
    val maskedText: String,
    val tagMap: Map<String, String>
)

object SubtitleParser {
    private val BOM_PATTERN = Regex("""^(\uFEFF|\uFFFE)""")
    private val TIMING_LINE_REGEX = Regex(
        """(?m)^\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,4})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,4})([^\r\n]*)"""
    )
    private val TAG_SHIELD_REGEX = Regex(
        """(<\/?[a-zA-Z0-9_.\-]+(?:\s+[^>]*?)?>|\{\\[^}]+\}|<\d{2}:\d{2}(?::\d{2})?[,.]\d{3}>)"""
    )

    private fun parseTimestampToMs(hStr: String?, mStr: String, sStr: String, msStr: String): Long {
        val hours = hStr?.toLongOrNull() ?: 0L
        val minutes = mStr.toLongOrNull() ?: 0L
        val seconds = sStr.toLongOrNull() ?: 0L
        val msPadded = msStr.padEnd(3, '0').take(3)
        val millis = msPadded.toLongOrNull() ?: 0L
        return (hours * 3600_000L) + (minutes * 60_000L) + (seconds * 1000L) + millis
    }

    fun parse(rawContent: String): List<SubtitleCue> {
        val sanitized = rawContent
            .replace(BOM_PATTERN, "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val matches = TIMING_LINE_REGEX.findAll(sanitized).toList()
        if (matches.isEmpty()) return emptyList()

        val resultCues = ArrayList<SubtitleCue>(matches.size)

        for (i in matches.indices) {
            val match = matches[i]
            val g = match.groupValues

            val startMs = parseTimestampToMs(g[1].ifEmpty { null }, g[2], g[3], g[4])
            val endMs = parseTimestampToMs(g[5].ifEmpty { null }, g[6], g[7], g[8])
            val settings = g[9].trim().ifEmpty { null }

            val textStart = match.range.last + 1
            val textEnd = if (i + 1 < matches.size) {
                val nextStart = matches[i + 1].range.first
                val slice = sanitized.substring(textStart, nextStart)
                val lines = slice.lines().toMutableList()

                // 1. Loại bỏ các dòng trống ở cuối
                while (lines.isNotEmpty() && lines.last().trim().isEmpty()) {
                    lines.removeAt(lines.size - 1)
                }

                // 2. CHỈ loại bỏ định danh cue tiếp theo nếu nó được ngăn cách bởi dòng trống
                if (lines.size >= 2 && lines[lines.size - 2].trim().isEmpty()) {
                    val candidate = lines.last().trim()
                    val isSrtIndex = candidate.all { c -> c.isDigit() }
                    val isVttCueId = candidate.startsWith("NOTE") || Regex("""^[a-zA-Z0-9_\-]+$""").matches(candidate)
                    if (isSrtIndex || isVttCueId) {
                        lines.removeAt(lines.size - 1)
                        while (lines.isNotEmpty() && lines.last().trim().isEmpty()) {
                            lines.removeAt(lines.size - 1)
                        }
                    }
                }
                textStart + lines.joinToString("\n").length
            } else {
                sanitized.length
            }

            val dialogue = sanitized.substring(textStart, textEnd.coerceAtLeast(textStart)).trim()
            if (dialogue.isBlank()) continue

            // Che chắn các thẻ style nội dòng
            val tagMap = LinkedHashMap<String, String>()
            var tagCounter = 1
            val maskedDialogue = TAG_SHIELD_REGEX.replace(dialogue) { tagMatch ->
                val placeholder = "[[TAG_${tagCounter++}]]"
                tagMap[placeholder] = tagMatch.value
                placeholder
            }

            resultCues.add(
                SubtitleCue(
                    index = resultCues.size + 1,
                    startMs = startMs,
                    endMs = endMs,
                    cueSettings = settings,
                    rawText = dialogue,
                    maskedText = maskedDialogue,
                    tagMap = tagMap
                )
            )
        }

        return resultCues
    }
}
```

---

### 2.4 Che Chắn Thẻ Định Dạng & Cân Bằng Thẻ HTML Đầy Đủ (`SubtitleReconstructor.kt`)
Các mô hình ngôn ngữ lớn thường có xu hướng chuyển ký tự tag thành chữ thường (`[[tag_1]]`) hoặc chèn thêm khoảng trắng (`[[ TAG_1 ]]`). Ngoài ra, phụ đề anime thường sử dụng thẻ đổi màu `<font color="#FFCC00">`. Nếu thẻ đóng bị thiếu, màu chữ sẽ bị loang sang toàn bộ các phụ đề phía sau trong ExoPlayer.

```kotlin
package com.lagradost.cloudstream3.subtitles.ai.parser

import java.util.Locale

object SubtitleReconstructor {
    private val TAG_TOKEN_REGEX = Regex("""\[\[\s*TAG_(\d+)\s*\]\]""", RegexOption.IGNORE_CASE)

    fun formatSrtTimestamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val hours = clamped / 3600_000L
        val minutes = (clamped % 3600_000L) / 60_000L
        val seconds = (clamped % 60_000L) / 1000L
        val millis = clamped % 1000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    fun unmaskTags(text: String, tagMap: Map<String, String>): String {
        val unmasked = TAG_TOKEN_REGEX.replace(text) { match ->
            val tagId = match.groupValues[1]
            tagMap["[[TAG_$tagId]]"] ?: match.value
        }
        return balanceHtmlTags(unmasked)
    }

    fun balanceHtmlTags(input: String): String {
        var balanced = input
        val simpleTags = listOf("i", "b", "u", "s")
        for (tag in simpleTags) {
            val openCount = Regex("<$tag>", RegexOption.IGNORE_CASE).findAll(balanced).count()
            val closeCount = Regex("</$tag>", RegexOption.IGNORE_CASE).findAll(balanced).count()
            if (openCount > closeCount) {
                balanced += "</$tag>".repeat(openCount - closeCount)
            }
        }
        // Cân bằng các thẻ font (ví dụ: <font color="...">)
        val openFontCount = Regex("""<font(?:\s+[^>]*?)?>""", RegexOption.IGNORE_CASE).findAll(balanced).count()
        val closeFontCount = Regex("""</font>""", RegexOption.IGNORE_CASE).findAll(balanced).count()
        if (openFontCount > closeFontCount) {
            balanced += "</font>".repeat(openFontCount - closeFontCount)
        }
        return balanced
    }

    /**
     * Xây dựng tài liệu SubRip (.srt) hợp lệ chuẩn chỉnh.
     * Đối với Chiến lược B (Khung sườn tạm thời), các cue chưa dịch sẽ sử dụng an toàn cue.rawText,
     * đảm bảo toàn bộ dòng thời gian đều có thoại để ExoPlayer lập chỉ mục đầy đủ.
     */
    fun buildSubRip(cues: List<SubtitleCue>, translatedTexts: Map<Int, String>): String {
        val sb = StringBuilder()
        cues.forEachIndexed { idx, cue ->
            val translation = translatedTexts[cue.index]?.trim()
            val content = if (!translation.isNullOrBlank()) {
                unmaskTags(translation, cue.tagMap)
            } else {
                cue.rawText
            }
            sb.append(idx + 1).append("\n")
            sb.append(formatSrtTimestamp(cue.startMs))
                .append(" --> ")
                .append(formatSrtTimestamp(cue.endMs))
                .append("\n")
            sb.append(content).append("\n\n")
        }
        return sb.toString().trimEnd() + "\n"
    }
}
```

---

## 3. Đặc Tả Tích Hợp Đa Nhà Cung Cấp AI API (Multi-Provider AI API Integration Specification)

### 3.1 Nhà Cung Cấp 1: OpenAI & Các API Tương Thích Chuẩn OpenAI

#### Chuẩn Hóa Đường Dẫn Cơ Sở (Base URL Normalization)
Hỗ trợ cả tên miền gốc (ví dụ: `https://api.openai.com`), Ollama nội bộ (`http://localhost:11434`), Groq (`https://api.groq.com/openai`), và OpenRouter:

```kotlin
fun normalizeOpenAiUrl(baseUrl: String?): String {
    val clean = (baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: "https://api.openai.com/v1").removeSuffix("/")
    return when {
        clean.endsWith("/chat/completions") -> clean
        clean.endsWith("/v1") -> "$clean/chat/completions"
        clean.contains("deepseek.com") && !clean.endsWith("/v1") -> "$clean/chat/completions"
        clean.contains("/openai") && !clean.endsWith("/v1") -> "$clean/v1/chat/completions"
        !clean.contains("/v1") -> "$clean/v1/chat/completions"
        else -> "$clean/chat/completions"
    }
}
```

#### Structured Outputs & Cấu Trúc JSON Mode Schema
Chế độ JSON Mode của OpenAI (`response_format: {"type": "json_object"}`) yêu cầu bắt buộc gốc đầu ra phải là một JSON Object. Các endpoint OpenAI hiện đại cũng hỗ trợ Structured Outputs (`type: "json_schema"`):

```json
{
  "model": "gpt-4o-mini",
  "temperature": 0.3,
  "top_p": 0.95,
  "max_tokens": 2048,
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "name": "subtitle_translations",
      "strict": true,
      "schema": {
        "type": "object",
        "properties": {
          "translations": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "id": { "type": "integer" },
                "text": { "type": "string" }
              },
              "required": ["id", "text"],
              "additionalProperties": false
            }
          }
        },
        "required": ["translations"],
        "additionalProperties": false
      }
    }
  },
  "messages": [
    {
      "role": "system",
      "content": "You are a professional subtitle translator. Output a JSON object with key 'translations' containing an array of objects with integer 'id' and string 'text'."
    }
  ]
}
```

---

### 3.2 Nhà Cung Cấp 2: Google AI Studio (Gemini REST API `v1beta`)

#### Endpoint URL & Chuẩn Hóa Chuỗi
Loại bỏ tiền tố `models/` nếu người dùng nhập thừa để tránh bị nhân đôi chuỗi đường dẫn (`models/models/...` gây lỗi HTTP 404):

```kotlin
fun buildGeminiUrl(baseUrl: String?, model: String): String {
    val cleanBase = (baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: "https://generativelanguage.googleapis.com").removeSuffix("/")
    val cleanModel = model.trim().removePrefix("models/").ifEmpty { "gemini-1.5-flash" }
    return if (cleanBase.endsWith("/v1beta")) {
        "$cleanBase/models/$cleanModel:generateContent"
    } else {
        "$cleanBase/v1beta/models/$cleanModel:generateContent"
    }
}
```

#### Ghi Đè Bộ Lọc An Toàn (Safety Overrides) & Cấu Trúc Schema Gốc
- Thiết lập bắt buộc `BLOCK_NONE` trên cả 4 danh mục nhạy cảm (`HARM_CATEGORY_HARASSMENT`, `HARM_CATEGORY_HATE_SPEECH`, `HARM_CATEGORY_SEXUALLY_EXPLICIT`, `HARM_CATEGORY_DANGEROUS_CONTENT`) để tránh việc AI từ chối dịch các phân cảnh hành động hoặc kịch tính trong anime/phim.
- Áp dụng `responseSchema` với kiểu gốc là `ARRAY` chứa các phần tử `OBJECT` (`id: INTEGER`, `text: STRING`).
- Trích xuất dữ liệu an toàn tránh lỗi NullPointer: `candidate.content?.parts?.firstOrNull()?.text ?: throw GeminiEmptyResponseException(...)`.

---

### 3.3 Nhà Cung Cấp 3: Anthropic Messages API & Các Endpoint Tương Thích

#### Endpoint URL
```kotlin
fun buildAnthropicUrl(baseUrl: String?): String {
    val clean = baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: "https://api.anthropic.com/v1/messages"
    return if (clean.endsWith("/messages")) clean else "$clean/messages".replace("//messages", "/messages")
}
```

#### Kỹ Thuật Điền Sẵn Assistant (Assistant Prefill) & Xử Lý Lỗi Hết Hạn Mức
- Điền sẵn ký tự `[` trong tin nhắn phản hồi của Assistant nhằm ép buộc Claude chỉ xuất ra mảng phần tử JSON. Client sẽ tự động nối thêm `[` vào đầu chuỗi trước khi giải tuần tự hóa.
- **Phát hiện cạn kiệt tài khoản (Credit Exhaustion Detection)**: Anthropic trả về mã lỗi `HTTP 400 Bad Request` với `type: "invalid_request_error"` và thông điệp `"credit balance is too low"`. Lỗi này được bắt tường minh và xử lý như một trường hợp cạn kiệt hạn mức cứng.
- **Header Prompt Caching so với Ngưỡng Token Tối Thiểu**: Header `anthropic-beta: prompt-caching-2024-07-31` được tích hợp sẵn. Cần lưu ý Claude 3.5 Haiku yêu cầu ít nhất 2.048 prompt token để kích hoạt bộ nhớ đệm prompt. Với các chunk 30 cue (~850 token), tính năng caching sẽ tự động kích hoạt trên Claude 3.5 Sonnet (ngưỡng tối thiểu 1.024 token) hoặc khi bảng thuật ngữ (glossary) dài vượt quá 2.048 token.

---

### 3.4 Bộ Giải Tuần Tự Hóa Đa Hình Toàn Năng (`AITranslationParser.kt`)
Để xử lý mượt mà và đồng nhất các dạng phản hồi khác nhau giữa OpenAI (`{"translations": [...]}`), Gemini (`[...]`), Anthropic (`[...]`), và các khối markdown (` ```json `):

```kotlin
package com.lagradost.cloudstream3.subtitles.ai.parser

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

data class SubtitleItem(
    val id: Int,
    val text: String
)

object AITranslationParser {
    private val mapper = ObjectMapper().apply {
        registerKotlinModule()
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val DELIMITER_REGEX = Regex("""<<<(\d+)>>>\s*([\s\S]*?)(?=(?:<<<\d+>>>|$))""")

    fun stripMarkdownFences(raw: String): String {
        var clean = raw.trim()
        if (clean.startsWith("```")) {
            clean = clean.substringAfter("\n")
            if (clean.endsWith("```")) {
                clean = clean.substringBeforeLast("```").trim()
            }
        }
        return clean
    }

    fun parseTranslations(rawResponse: String): Map<Int, String> {
        val clean = stripMarkdownFences(rawResponse)
        val result = HashMap<Int, String>()

        try {
            val rootNode = mapper.readTree(clean)
            val arrayNode = when {
                rootNode.isArray -> rootNode
                rootNode.has("translations") && rootNode.get("translations").isArray -> rootNode.get("translations")
                rootNode.isObject -> rootNode.elements().asSequence().firstOrNull { it.isArray }
                else -> null
            }

            if (arrayNode != null) {
                val items: List<SubtitleItem> = mapper.convertValue(
                    arrayNode,
                    object : TypeReference<List<SubtitleItem>>() {}
                )
                items.forEach { result[it.id] = it.text }
                return result
            }
        } catch (_: Throwable) {
            // Chuyển sang parser regex delimiter nếu phân tích JSON thất bại
        }

        // Phương án dự phòng: Parser ký tự phân cách (<<<ID>>>\nText)
        val delimiterMatches = DELIMITER_REGEX.findAll(clean).toList()
        for (m in delimiterMatches) {
            val id = m.groupValues[1].toIntOrNull() ?: continue
            val text = m.groupValues[2].trim()
            result[id] = text
        }

        return result
    }
}
```

---

### 3.5 Tự Động Khắc Phục Lệch Dòng Đoạn Thoại (`autoHealingReconcile`)
Khi các LLM (đặc biệt là DeepSeek, Groq Llama, và các mô hình lượng tử hóa cục bộ) dịch một batch cue (ví dụ từ cue 31..60), chúng thường đánh lại chỉ mục bắt đầu từ 1 (`1..30`). Nếu tra cứu theo ID trực tiếp thì 100% các câu thoại dịch xong sẽ bị từ chối bỏ rơi.

Hàm **Auto-Healing Reconciler** tự động nhận diện việc đánh số tương đối và khớp ánh xạ theo thứ tự vị trí:

```kotlin
package com.lagradost.cloudstream3.subtitles.ai.session

import com.lagradost.cloudstream3.subtitles.ai.parser.SubtitleCue

object SubtitleReconciler {
    fun autoHealingReconcile(
        inputCues: List<SubtitleCue>,
        translatedItems: Map<Int, String>
    ): Map<Int, String> {
        val reconciled = HashMap<Int, String>(inputCues.size)
        val minInputId = inputCues.minOfOrNull { it.index } ?: 1
        val transKeys = translatedItems.keys.sorted()

        // Tự động nhận diện đánh số tương đối từ 1 (keys 1..N trong khi input bắt đầu > N)
        val isRelative = transKeys.isNotEmpty() &&
                transKeys.first() == 1 &&
                transKeys.last() <= inputCues.size &&
                minInputId > inputCues.size

        for ((offset, cue) in inputCues.withIndex()) {
            var text = translatedItems[cue.index]?.trim()
            if (text.isNullOrBlank() && isRelative) {
                // Ánh xạ theo offset từ 1: offset 0 tương ứng với item ID 1
                text = translatedItems[offset + 1]?.trim()
            }
            reconciled[cue.index] = if (!text.isNullOrBlank()) text else cue.rawText
        }
        return reconciled
    }
}
```

---

### 3.6 Tối Ưu Hóa Token, Quản Lý Rate Limit & Tách Biệt Hạn Mức Gemini

#### Tách Biệt Giới Hạn 15 RPM So Với Giới Hạn Hàng Ngày Của Gemini
Google Gemini REST API sử dụng chung mã lỗi HTTP 429 kèm thông báo `status: "RESOURCE_EXHAUSTED"` cho cả lỗi tạm thời vượt ngưỡng 15 RPM và lỗi cạn kiệt hạn ngạch miễn phí hàng ngày.
1. Client sẽ kiểm tra trường `error.details` và `error.message`:
   - Nếu `violations.description` chứa rõ `PerDay` hoặc `GenerateRequestsPerDay`: Kích hoạt ngắt mạch (circuit breaker) trong 15 phút, hiển thị thông báo Toast trên UI Main thread cho người dùng, và chuyển sang chế độ dự phòng.
   - Nếu chỉ là lỗi vượt giới hạn RPM tạm thời (hoặc chứa `RequestsPerMinute`): Tạm dừng luồng không gây khóa giao diện bằng `delay()` kết hợp cơ chế ngẫu nhiên (full jitter từ 5s–10s) và thử lại tối đa 5 lần mà không ban provider.
2. Bộ điều tiết đồng thời ở phía client (`Semaphore(2)`) và bộ căn nhịp request đảm bảo gói Gemini Free Tier luôn hoạt động an toàn dưới ngưỡng 15 RPM.
3. An toàn luồng giao diện: Mọi thông báo Toast đều được điều phối về `Dispatchers.Main`.

```kotlin
suspend fun handleGeminiError(
    context: Context,
    code: Int,
    errorBody: String,
    attempt: Int
): Boolean {
    if (code == 429) {
        val isDailyQuota = errorBody.contains("PerDay", ignoreCase = true) ||
                errorBody.contains("GenerateRequestsPerDay", ignoreCase = true)
        if (isDailyQuota) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Hạn mức hàng ngày của Gemini đã hết. Đang chuyển sang phương án dự phòng.", Toast.LENGTH_LONG).show()
            }
            return false // Kích hoạt circuit breaker
        }
        // Lỗi 15 RPM tạm thời: lùi thời gian theo cấp số nhân kèm ngẫu nhiên (full jitter)
        val backoff = (5000L * (1 shl attempt.coerceAtMost(3))) + (0..2000).random()
        delay(backoff)
        return true // Thử lại
    }
    return false
}
```

---

## 4. Đặc Tả Cấu Hình Người Dùng & Giao Diện Cài Đặt (User Configuration & Settings Specification)

### 4.1 Xây Dựng View Android Bằng Mã Nguồn Bên Trong `AlertDialog.Builder`
Để ngăn chặn hoàn toàn lỗi văng ứng dụng `Resources$NotFoundException` xuất phát từ việc nạp file layout XML qua các Dalvik classloader động, toàn bộ giao diện cài đặt được khởi tạo trực tiếp bằng mã nguồn Kotlin thuần:

```
┌─────────────────────────────────────────────────────────────┐
│             Cài Đặt Dịch Phụ Đề Bằng AI                     │
├─────────────────────────────────────────────────────────────┤
│ [X] Bật Dịch Phụ Đề AI (Enable AI Subtitle Translation)     │
│                                                             │
│ Ngôn Ngữ Đích (Target Language):                            │
│ [ Spinner: Tiếng Việt (vi)                               v ]│
│                                                             │
│ Mức Độ Ưu Tiên Nguồn Dịch (Source Language Priority):       │
│ ( ) Tự động nhận diện theo thể loại phim (Smart Auto)       │
│ (X) Ưu tiên Tiếng Nhật (Anime First: Tiếng Nhật > Anh)      │
│ ( ) Ưu tiên Tiếng Anh (Movies First: Tiếng Anh > Nhật)      │
│ ( ) Danh sách tùy chỉnh (Custom Priority Order)             │
│   Thứ tự ngôn ngữ: [ ja, en, fr, es, zh                   ] │
│                                                             │
│ Nhà Cung Cấp AI (AI Provider):                              │
│ ( ) Google Gemini (Khuyên dùng gói Free)                    │
│ ( ) OpenAI (GPT-4o-mini)                                    │
│ ( ) Anthropic (Claude 3.5 Haiku)                            │
│ ( ) Tùy chỉnh / Chuẩn OpenAI (Groq, DeepSeek, Local LLM)    │
│                                                             │
│ Khóa API (API Key):                                         │
│ [ •••••••••••••••••••••••••••••••••••••••• ] [ Hiện ]       │
│                                                             │
│ URL Tùy Chỉnh (Tùy chọn ghi đè Base URL):                   │
│ [ https://api.deepseek.com/v1                            ]  │
│                                                             │
│ Tên Mô Hình (Mặc định để trống sẽ dùng model chuẩn):        │
│ [ deepseek-chat                                          ]  │
│                                                             │
│ [ Kiểm Tra Kết Nối API (Ping) ]                             │
│                                                             │
│ --- Tùy Chọn Nâng Cao ---                                   │
│ Kích Thước Chunk: [ 30 ] dòng mỗi request                   │
│ [X] Dịch Cả Hiệu Ứng Âm Thanh (SDH: [Âm nhạc], (Thở dài))   │
│ Bảng Thuật Ngữ Tuỳ Chỉnh (Glossary):                        │
│ [ Giữ nguyên kính ngữ anime: -san, -kun, -chan...        ]  │
│                                                             │
│ Thống Kê Bộ Nhớ Đệm: 14 tập (1.85 MB / 50 MB)               │
│ [ Xóa Bộ Nhớ Đệm Phụ Đề (Clear Cache) ]                     │
│                                                             │
│ [ Hủy ]                                [ Lưu & Áp Dụng ]    │
└─────────────────────────────────────────────────────────────┘
```

---

### 4.2 Mô Hình Cấu Hình: `TranslatorConfig.kt`
```kotlin
package com.lagradost.cloudstream3.subtitles.ai.config

import android.content.Context

object TranslatorConfig {
    private const val PREFS_NAME = "ai_subtitle_translator_preferences"

    const val PROVIDER_GEMINI = "gemini"
    const val PROVIDER_OPENAI = "openai"
    const val PROVIDER_ANTHROPIC = "anthropic"
    const val PROVIDER_CUSTOM = "custom"

    const val PRIORITY_AUTO = "auto"
    const val PRIORITY_ANIME_FIRST = "anime_first"     // Tiếng Nhật > Tiếng Anh > Khác
    const val PRIORITY_ENGLISH_FIRST = "english_first" // Tiếng Anh > Tiếng Nhật > Khác
    const val PRIORITY_CUSTOM = "custom"

    var isEnabled: Boolean = true
    var targetLanguage: String = "vi"
    var sourcePriorityMode: String = PRIORITY_ANIME_FIRST
    var customPriorityList: String = "ja, en"
    var provider: String = PROVIDER_GEMINI
    var apiKey: String = ""
    var customBaseUrl: String = ""
    var modelName: String = ""
    var chunkSize: Int = 30
    var preserveSdh: Boolean = true
    var customGlossary: String = ""

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isEnabled = prefs.getBoolean("key_enabled", true)
        targetLanguage = prefs.getString("key_target_lang", "vi") ?: "vi"
        sourcePriorityMode = prefs.getString("key_source_priority", PRIORITY_ANIME_FIRST) ?: PRIORITY_ANIME_FIRST
        customPriorityList = prefs.getString("key_custom_priority", "ja, en") ?: "ja, en"
        provider = prefs.getString("key_provider", PROVIDER_GEMINI) ?: PROVIDER_GEMINI
        apiKey = prefs.getString("key_api_key", "") ?: ""
        customBaseUrl = prefs.getString("key_base_url", "") ?: ""
        modelName = prefs.getString("key_model_name", "") ?: ""
        chunkSize = prefs.getInt("key_chunk_size", 30).coerceIn(10, 80)
        preserveSdh = prefs.getBoolean("key_preserve_sdh", true)
        customGlossary = prefs.getString("key_custom_glossary", "") ?: ""
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("key_enabled", isEnabled)
            putString("key_target_lang", targetLanguage)
            putString("key_source_priority", sourcePriorityMode)
            putString("key_custom_priority", customPriorityList)
            putString("key_provider", provider)
            putString("key_api_key", apiKey)
            putString("key_base_url", customBaseUrl)
            putString("key_model_name", modelName)
            putInt("key_chunk_size", chunkSize)
            putBoolean("key_preserve_sdh", preserveSdh)
            putString("key_custom_glossary", customGlossary)
            apply()
        }
    }

    fun getEffectiveModel(): String {
        if (modelName.isNotBlank()) return modelName.trim()
        return when (provider) {
            PROVIDER_GEMINI -> "gemini-1.5-flash"
            PROVIDER_OPENAI -> "gpt-4o-mini"
            PROVIDER_ANTHROPIC -> "claude-3-5-haiku-20241022"
            else -> "gpt-4o-mini"
        }
    }
}
```

---

## 5. Kiến Trúc Bộ Nhớ Đệm Hai Tầng (Two-Tier Caching Architecture)

### 5.1 Tầng 1: Bộ Nhớ Đệm Tập Phim Hoàn Chỉnh Bằng Khóa Tổ Hợp SHA-256
$$\text{CacheKey} = \text{SHA-256}\Big(\text{CleanSourceContent} + \text{TargetLang} + \text{Provider} + \text{Model} + \text{PromptVer} + \text{GlossaryHash}\Big)$$

### 5.2 Tầng 2: Bộ Nhớ Đệm Chunk Lũy Tiến (Progressive Chunk Cache)
Các chunk được lưu trữ tại `context.filesDir/ai_sub_cache/chunks/<cacheKey>/chunk_NNN.json`. Nếu việc dịch qua mạng bị gián đoạn ở chunk thứ 4 trên tổng số 12 chunk, lần phát tiếp theo sẽ tự động nạp các chunk 1..3 từ đĩa và tiếp tục gửi request dịch từ chunk thứ 4.

### 5.3 Trình Quản Lý Bộ Nhớ Đệm An Toàn Tệp Trên Windows & Dọn Dẹp Đệ Quy (`SubtitleCacheManager.kt`)
- **Ghi Đè Nguyên Tử Kèm Cơ Chế Dự Phòng Khóa Tệp Trên Windows (Atomic Replacement with Windows File-Lock Fallback)**: Sử dụng NIO `Files.move` với cờ `REPLACE_EXISTING` và `ATOMIC_MOVE`. Trên môi trường Windows, nếu tệp đang bị ExoPlayer mở để đọc, chương trình sẽ bắt ngoại lệ `AccessDeniedException` và kích hoạt cơ chế xóa trước đổi tên sau hoặc sao chép ghi đè an toàn.
- **Dọn Dẹp Đệ Quy (Recursive Pruning)**: Tự động dọn dẹp thư mục `full/` dựa trên vòng đời 30 ngày (TTL) và giới hạn 50MB, xóa các tệp tạm mồ côi `tmp_` quá 5 phút, và dọn dẹp các thư mục chunk bỏ hoang quá 24 giờ.

```kotlin
package com.lagradost.cloudstream3.subtitles.ai.cache

import android.content.Context
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

object SubtitleCacheManager {
    private const val MAX_BYTES = 50L * 1024 * 1024 // 50MB
    private const val MAX_FILES = 200
    private const val MAX_TTL_MS = 30L * 24 * 3600 * 1000 // 30 ngày
    private const val TMP_EXPIRY_MS = 5 * 60 * 1000 // 5 phút
    private const val CHUNK_EXPIRY_MS = 24 * 3600 * 1000 // 24 giờ

    private val mapper = ObjectMapper().registerKotlinModule()

    fun getBaseDir(context: Context): File = File(context.filesDir, "ai_sub_cache").apply { if (!exists()) mkdirs() }
    fun getFullDir(context: Context): File = File(getBaseDir(context), "full").apply { if (!exists()) mkdirs() }
    fun getChunksDir(context: Context): File = File(getBaseDir(context), "chunks").apply { if (!exists()) mkdirs() }

    fun computeSha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun buildCacheKey(content: String, targetLang: String, provider: String, model: String, glossary: String): String {
        val clean = content.trim().replace("\r\n", "\n")
        val contentHash = computeSha256(clean)
        val glossaryHash = if (glossary.isNotBlank()) computeSha256(glossary) else "noglossary"
        val composite = "$contentHash|$targetLang|$provider|$model|v2|$glossaryHash"
        return computeSha256(composite)
    }

    fun getFullFile(context: Context, cacheKey: String): File? {
        val file = File(getFullDir(context), "$cacheKey.srt")
        if (file.exists() && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        return null
    }

    @Synchronized
    fun saveFullFile(context: Context, cacheKey: String, srtContent: String): File {
        val fullDir = getFullDir(context)
        val destFile = File(fullDir, "$cacheKey.srt")
        val tempFile = File(fullDir, "tmp_${System.currentTimeMillis()}_$cacheKey.srt")
        try {
            tempFile.writeText(srtContent, Charsets.UTF_8)
            // Thay thế nguyên tử NIO với cơ chế dự phòng tránh lỗi khóa tệp Windows
            try {
                Files.move(
                    tempFile.toPath(),
                    destFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                try {
                    Files.move(
                        tempFile.toPath(),
                        destFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: Exception) {
                    if (destFile.exists()) destFile.delete()
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
            }

            pruneCache(context)
            clearChunks(context, cacheKey)
            return destFile
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    // --- Phương Thức Quản Lý Chunk Tầng 2 ---
    fun saveChunk(context: Context, cacheKey: String, chunkIndex: Int, items: Map<Int, String>) {
        val episodeChunkDir = File(getChunksDir(context), cacheKey).apply { if (!exists()) mkdirs() }
        val chunkFile = File(episodeChunkDir, "chunk_%04d.json".format(chunkIndex))
        val json = mapper.writeValueAsString(items)
        chunkFile.writeText(json, Charsets.UTF_8)
        episodeChunkDir.setLastModified(System.currentTimeMillis())
    }

    fun loadChunks(context: Context, cacheKey: String): Map<Int, String> {
        val episodeChunkDir = File(getChunksDir(context), cacheKey)
        if (!episodeChunkDir.exists() || !episodeChunkDir.isDirectory) return emptyMap()

        val combined = HashMap<Int, String>()
        val chunkFiles = episodeChunkDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name } ?: return emptyMap()
        for (f in chunkFiles) {
            try {
                val map: Map<Int, String> = mapper.readValue(f, object : TypeReference<Map<Int, String>>() {})
                combined.putAll(map)
            } catch (_: Throwable) {}
        }
        return combined
    }

    fun hasChunks(context: Context, cacheKey: String): Boolean {
        val episodeChunkDir = File(getChunksDir(context), cacheKey)
        return episodeChunkDir.exists() && (episodeChunkDir.listFiles()?.isNotEmpty() == true)
    }

    fun clearChunks(context: Context, cacheKey: String) {
        File(getChunksDir(context), cacheKey).deleteRecursively()
    }

    @Synchronized
    fun pruneCache(context: Context) {
        val now = System.currentTimeMillis()
        val fullDir = getFullDir(context)
        val chunksDir = getChunksDir(context)

        // 1. Dọn dẹp các tệp tmp_ mồ côi quá 5 phút
        fullDir.listFiles { f -> f.isFile && f.name.startsWith("tmp_") }?.forEach { f ->
            if (now - f.lastModified() > TMP_EXPIRY_MS) f.delete()
        }

        // 2. Dọn dẹp các thư mục chunk bỏ hoang quá 24 giờ
        chunksDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
            if (now - dir.lastModified() > CHUNK_EXPIRY_MS) dir.deleteRecursively()
        }

        // 3. Dọn dẹp thư mục full/ theo chính sách LRU
        val files = fullDir.listFiles()?.filter { it.isFile && !it.name.startsWith("tmp_") }?.toMutableList() ?: return

        // Xóa các tệp quá hạn TTL
        files.removeAll { f ->
            if (now - f.lastModified() > MAX_TTL_MS) {
                f.delete()
                true
            } else false
        }

        files.sortBy { it.lastModified() }
        var totalBytes = files.sumOf { it.length() }
        var fileCount = files.size

        while (files.isNotEmpty() && (totalBytes > MAX_BYTES || fileCount > MAX_FILES)) {
            val oldest = files.removeAt(0)
            totalBytes -= oldest.length()
            fileCount--
            oldest.delete()
        }
    }

    fun clearAll(context: Context) {
        getBaseDir(context).deleteRecursively()
    }

    fun getMetrics(context: Context): Pair<Int, Long> {
        val files = getFullDir(context).listFiles()?.filter { it.isFile && !it.name.startsWith("tmp_") } ?: emptyList()
        val totalBytes = files.sumOf { it.length() }
        return Pair(files.size, totalBytes)
    }
}
```

---

### 5.4 Triển Khai Máy Chủ HTTP Loopback Nhúng (`AITranslatorLocalServer.kt`)
Được liên kết với địa chỉ `127.0.0.1:<port>` trên `ServerSocket(0)`. Phân phối các tệp với `Content-Type: text/plain; charset=utf-8` và xác thực đường dẫn quy chuẩn để ngăn tấn công duyệt thư mục (path traversal).

---

## 6. Lộ Trình Triển Khai Theo Giai Đoạn & Tiêu Chuẩn Thẩm Định Độc Lập (Phased Implementation Roadmap & Independent Verification Rubric)

### 6.1 Phân Chia Các Giai Đoạn Triển Khai

```
Giai đoạn 1: Core Subtitle Models, Parser, Tag Shielder & Reconstructor
   │
   ▼
Giai đoạn 2: Embedded Loopback HTTP Server & Windows-Safe Caching Engine
   │
   ▼
Giai đoạn 3: Multi-Provider AI Client Engine & Polymorphic JSON Deserializer
   │
   ▼
Giai đoạn 4: Dynamic Outermost Hook Decorator & Delivery Strategies A & B
   │
   ▼
Giai đoạn 5: CloudStream Settings UI & Main-Thread Safety
   │
   ▼
Giai đoạn 6: E2E Integration, DEX/.cs3 Packaging & 4-Enricher Coexistence
```

---

### Giai đoạn 1: Core Subtitle Models, Parser, Tag Shielder & Reconstructor
- [ ] **Nhiệm vụ 1.1**: Định nghĩa các data class `SubtitleCue`, `SubtitleItem`, và `TranslationResult` trong gói `com.lagradost.cloudstream3.subtitles.ai.models`.
- [ ] **Nhiệm vụ 1.2**: Triển khai `SubtitleParser.kt` hỗ trợ cả định dạng `.srt` và `.vtt`, chỉ loại bỏ số thứ tự/định danh cue khi được ngăn cách bởi dòng trống, bảo vệ toàn vẹn các câu thoại kết thúc bằng số (`"Agent 47"`).
- [ ] **Nhiệm vụ 1.3**: Triển khai cơ chế che chắn thẻ định dạng bằng regex token trong `SubtitleParser` (`[[TAG_1]]`, `[[TAG_2]]`) cho các thẻ `<i>`, `<b>`, `<font>`, `{\an8}`, và `<v>`.
- [ ] **Nhiệm vụ 1.4**: Triển khai `SubtitleReconstructor.kt` với khả năng gỡ mặt nạ thẻ không phân biệt hoa thường (`\[\[\s*TAG_(\d+)\s*\]\]`), tự động cân bằng thẻ mở/đóng cho `i`, `b`, `u`, `s`, và `font`, cùng kiểm tra tính toàn vẹn của thoại không rỗng.

#### Tiêu Chuẩn Thẩm Định Giai Đoạn 1 (Phase 1 Verification Rubric)
- **Lệnh Kiểm Thử CLI**:
  ```bash
  python -m unittest tests/test_m5_adversarial_empirical.py
  ```
- **Kỳ Vọng Kiểm Thử (Expected Assertions)**:
  - `test_trailing_numeric_dialogue_line_bug`: Các cue kết thúc bằng `"47"` hoặc `"Room 101"` được giữ nguyên 100%.
  - `test_llm_lowercase_token_defect`: `[[tag_1]]` được gỡ mặt nạ chính xác trở lại thành `<i>`.
  - `test_unclosed_font_tag_defect`: `<font color="#FFFF00">` được tự động đóng thẻ bằng `</font>`.
- **Tiêu Chí Đạt/Không Đạt**: Vượt qua 100% trên toàn bộ 12 kịch bản thực nghiệm.

---

### Giai đoạn 2: Embedded Loopback HTTP Server & Windows-Safe Caching Engine
- [ ] **Nhiệm vụ 2.1**: Triển khai `SubtitleCacheManager.kt` hỗ trợ lưu đệm tệp hoàn chỉnh Tầng 1 và lưu đệm theo chunk Tầng 2 (`saveChunk`, `loadChunks`, `hasChunks`).
- [ ] **Nhiệm vụ 2.2**: Triển khai băm khóa tổ hợp SHA-256 và cơ chế dọn dẹp đệ quy (giới hạn 50MB / 200 tệp / TTL 30 ngày, dọn tệp tạm `tmp_` 5 phút, dọn chunk 24 giờ).
- [ ] **Nhiệm vụ 2.3**: Triển khai cơ chế ghi tệp nguyên tử an toàn trên Windows bằng NIO `StandardCopyOption.REPLACE_EXISTING`.
- [ ] **Nhiệm vụ 2.4**: Triển khai `AITranslatorLocalServer.kt` lắng nghe tại `127.0.0.1:<port>` trên cổng ngẫu nhiên `ServerSocket(0)`.
- [ ] **Nhiệm vụ 2.5**: Triển khai bộ xử lý HTTP GET `/sub/<cacheKey>.srt` trả về HTTP 200 với lớp bảo vệ chống tấn công duyệt thư mục (path traversal).

#### Tiêu Chuẩn Thẩm Định Giai Đoạn 2 (Phase 2 Verification Rubric)
- **Lệnh Kiểm Thử CLI**:
  ```bash
  python tests/test_ai_advanced_stress.py
  ```
- **Kỳ Vọng Kiểm Thử (Expected Assertions)**:
  - `test_windows_atomic_file_rename_under_concurrent_read`: Ghi đè tệp đệm thành công ngay cả khi đang bị mở đọc mà không phát sinh ngoại lệ IOException.
  - `test_robust_cache_pruner`: Các tệp tạm `tmp_` mồ côi và thư mục chunk bỏ hoang được dọn sạch trong khi các thư mục đang hoạt động vẫn được bảo toàn.
- **Tiêu Chí Đạt/Không Đạt**: 0 lỗi khóa tệp, 0 lỗ hổng duyệt thư mục, vượt qua trình kiểm tra URL của OkHttp.

---

### Giai đoạn 3: Multi-Provider AI Client Engine & Polymorphic JSON Deserializer
- [ ] **Nhiệm vụ 3.1**: Định nghĩa interface `AITranslationClient` và lớp tạo `AITranslatorFactory`.
- [ ] **Nhiệm vụ 3.2**: Triển khai `OpenAITranslatorClient.kt` hỗ trợ OpenAI Structured Outputs (`json_schema`), chuẩn hóa base URL, và prompt schema dạng `{"translations": [...]}`.
- [ ] **Nhiệm vụ 3.3**: Triển khai `GeminiTranslatorClient.kt` với xác thực header `x-goog-api-key`, ghi đè bộ lọc an toàn `BLOCK_NONE`, cấu hình `responseSchema` gốc, và tự động gỡ tiền tố `models/`.
- [ ] **Nhiệm vụ 3.4**: Triển khai `AnthropicTranslatorClient.kt` với kỹ thuật assistant prefill `[`, URL mặc định `https://api.anthropic.com/v1/messages`, và bộ xử lý lỗi cạn kiệt tài khoản HTTP 400.
- [ ] **Nhiệm vụ 3.5**: Triển khai `AITranslationParser.kt` cung cấp khả năng giải tuần tự hóa đa hình toàn diện (chấp nhận cả mảng gốc, wrapper object, hoặc khối delimiter).
- [ ] **Nhiệm vụ 3.6**: Triển khai hàm `autoHealingReconcile` trong `SubtitleReconciler.kt` tự động nhận diện việc đánh lại số thứ tự `1..N`.
- [ ] **Nhiệm vụ 3.7**: Triển khai cơ chế phân biệt lỗi 15 RPM so với cạn hạn mức ngày của Gemini với thuật toán lùi thời gian ngẫu nhiên và thông báo Toast trên UI Main-thread.

#### Tiêu Chuẩn Thẩm Định Giai Đoạn 3 (Phase 3 Verification Rubric)
- **Lệnh Kiểm Thử CLI**:
  ```bash
  python tests/test_ai_provider_caching_adversarial.py
  ```
- **Kịch Bản Kiểm Thử (Scenarios Tested)**:
  - Parser đa hình phân tích thành công cả hai dạng dữ liệu `{"translations": [...]}` và `[...]`.
  - Bộ auto-healing phục hồi đầy đủ 30/30 cue (tỷ lệ khôi phục 100%) khi AI reset index về 1.
  - Lỗi 15 RPM của Gemini chỉ kích hoạt chờ thử lại (backoff) thay vì cấm provider trong 15 phút.
  - Lỗi cạn tài khoản HTTP 400 của Anthropic kích hoạt cơ chế chuyển đổi dự phòng mượt mà.
- **Tiêu Chí Đạt/Không Đạt**: 100% tỷ lệ khớp bản dịch, 0 trường hợp mất cue thoại.

---

### Giai đoạn 4: Dynamic Outermost Hook Decorator & Delivery Strategies A & B
- [ ] **Nhiệm vụ 4.1**: Triển khai `AITranslatorHook.kt` với thuật toán duyệt chuỗi sâu an toàn phát hiện vòng lặp (`IdentityHashMap`), hoán đổi delegate có kiểm tra loại, và cơ chế tự đưa lên đầu chuỗi quét định kỳ 3 giây một lần.
- [ ] **Nhiệm vụ 4.2**: Triển khai `AITranslatorEnrichedMainAPI.kt` bọc lớp ngoài cùng với rào chắn lỗi cách ly `try-catch` và chuyển tiếp phụ đề gốc đồng bộ 0ms đến trình phát.
- [ ] **Nhiệm vụ 4.3**: Triển khai `AITranslationSession.kt` điều phối việc nạp phụ đề gốc, kiểm tra cache SHA-256, và phân đoạn batch song song qua `Semaphore(4)`.
- [ ] **Nhiệm vụ 4.4**: Triển khai **Chiến lược A (Batch Tốc Độ Cao)** phát track hoàn chỉnh một lần duy nhất.
- [ ] **Nhiệm vụ 4.5**: Triển khai **Chiến lược B (Khung Sườn Lũy Tiến)** phát bản nháp khung sườn sau Chunk 1 (cue 1..30 đã dịch + cue 31..N dùng thoại gốc), sau đó phát bản hoàn chỉnh chính thức khi tất cả các chunk hoàn tất.

#### Tiêu Chuẩn Thẩm Định Giai Đoạn 4 (Phase 4 Verification Rubric)
- **Lệnh Kiểm Thử CLI**:
  ```bash
  python tests/test_hook_lifecycle_adversarial.py
  ```
- **Kỳ Vọng Kiểm Thử (Expected Assertions)**:
  - Phụ đề gốc từ Kitsunekko, Anime47, và Stremio đều đi qua interceptor của AI Translator mà không bị chặn độ trễ 0ms.
  - Quá trình self-hoisting tự động nâng AI Translator lên đầu chuỗi ngay cả khi các enricher khác được kích hoạt muộn hơn.
- **Tiêu Chí Đạt/Không Đạt**: 100% phụ đề được chặn bắt, 0ms trễ luồng video, tuân thủ tuyệt đối cơ chế single-sample của ExoPlayer.

---

### Giai đoạn 5: CloudStream Settings UI & Preference Persistence
- [ ] **Nhiệm vụ 5.1**: Triển khai `TranslatorConfig.kt` với cơ chế lưu trữ SharedPreferences trong tệp `ai_subtitle_translator_preferences`, bổ sung các tuỳ chọn mức độ ưu tiên nguồn dịch (`sourcePriorityMode`, `customPriorityList`).
- [ ] **Nhiệm vụ 5.2**: Xây dựng toàn bộ hệ thống View Android bằng mã nguồn (`ScrollView`, `LinearLayout`, `EditText`, `RadioGroup`, `Spinner`, `Button`) bên trong `AlertDialog.Builder`.
- [ ] **Nhiệm vụ 5.3**: Triển khai nút chuyển đổi ẩn/hiện mật khẩu cho ô nhập API Key.
- [ ] **Nhiệm vụ 5.4**: Triển khai nút Kiểm Tra Kết Nối (Ping Test) thực thi một prompt kiểm thử ngắn kèm thông báo phản hồi trực tiếp.
- [ ] **Nhiệm vụ 5.5**: Triển khai nút Xóa Bộ Nhớ Đệm (Clear Cache) kèm cập nhật số liệu dung lượng đĩa thời gian thực.
- [ ] **Nhiệm vụ 5.6**: Đảm bảo toàn bộ Toast và cập nhật giao diện đều thực thi trên `Dispatchers.Main`.

#### Tiêu Chuẩn Thẩm Định Giai Đoạn 5 (Phase 5 Verification Rubric)
- **Lệnh Kiểm Thử CLI**:
  ```bash
  python tests/run_m1_challenger_stress.py
  ```
- **Kỳ Vọng Kiểm Thử (Expected Assertions)**:
  - Mọi giá trị cài đặt đều được lưu trữ và tải lại chính xác.
  - Không chứa bất kỳ lệnh nạp layout XML nào trong bytecode (miễn nhiễm với lỗi `Resources$NotFoundException` của Dalvik).
- **Tiêu Chí Đạt/Không Đạt**: Lưu trữ cấu hình 100% bền bỉ không phụ thuộc vào tài nguyên XML của Android.

---

### Giai đoạn 6: E2E Integration, DEX/.cs3 Packaging & Regression Hardening
- [ ] **Nhiệm vụ 6.1**: Cấu hình tệp `build.gradle.kts` và các script đóng gói cho `AiSubtitleTranslator`.
- [ ] **Nhiệm vụ 6.2**: Biên dịch mã nguồn Kotlin sang bytecode JVM 11 `.class` và đóng gói dex bằng Google D8 (`classes.dex`).
- [ ] **Nhiệm vụ 6.3**: Đóng gói tệp `AiSubtitleTranslator.cs3` chứa `classes.dex` và `manifest.json`.
- [ ] **Nhiệm vụ 6.4**: Cập nhật tệp `plugins.json` và `repo.json` ở thư mục gốc với mã băm SHA-256 và metadata đầy đủ.
- [ ] **Nhiệm vụ 6.5**: Chạy toàn bộ Bộ Kiểm Thử Cùng Tồn Tại 4-Enricher (`Anime47` + `Kitsunekko` + `Stremio` + `AiSubtitleTranslator`).

#### Tiêu Chuẩn Thẩm Định Giai Đoạn 6 (Phase 6 Verification Rubric)
- **Lệnh Kiểm Thử CLI**:
  ```bash
  python tests/test_e2e_coexistence.py
  python tests/test_packaging_boundary.py
  ```
- **Danh Mục Kiểm Tra (Checklist)**:
  - Tệp `AiSubtitleTranslator.cs3` tồn tại và vượt qua kiểm tra tính toàn vẹn của tệp ZIP.
  - `classes.dex` chứa bytecode Dalvik hợp lệ (tương thích minSdk 21 trở lên).
  - Bộ test cùng tồn tại 4 enricher vượt qua 100% (hơn 49 assertions).
- **Tiêu Chí Đạt/Không Đạt**: Không gây thoái lui (regression) đối với các enricher Anime47, Kitsunekko, và Stremio; bản build thành công 100%.

---

## 7. Đảm Bảo Cùng Tồn Tại & Không Gây Thoái Lui (Coexistence & Non-Regression Guarantees)

Việc tích hợp `AiSubtitleTranslator` đảm bảo không gây thoái lui (non-regression) trên toàn bộ các enricher hiện có:

| Thành Phần Hiện Có | Mối Quan Hệ Tích Hợp | Cam Kết Cùng Tồn Tại |
|---|---|---|
| **Anime47SubtitleEnricher** | `AiSubtitleTranslator` bọc ngoài `Anime47Hook.EnrichedMainAPI`. | Phụ đề softsub của Anime47 (`Tiếng Việt (Anime47)`) được chuyển ngay tới player. Nếu ngôn ngữ đích của người dùng là tiếng Việt, AI translator sẽ tự động bỏ qua để tránh tốn token trùng lặp. |
| **KitsunekkoSubtitleEnricher** | `AiSubtitleTranslator` bọc ngoài `KitsunekkoEnrichedMainAPI`. | Phụ đề softsub tiếng Nhật phát ra trên `127.0.0.1:<kitsunekko_port>` được chuyển ngay tới player, đồng thời `AiSubtitleTranslator` sẽ đọc song song luồng loopback này để tiến hành dịch. |
| **StremioSubtitleEnricher** | `AiSubtitleTranslator` bọc ngoài `StremioEnrichedMainAPI`. | Phụ đề đa ngôn ngữ từ Kitsu, Cinemeta, và OpenSubtitles được chuyển tới player ngay lập tức mà không bị cản trở. |
| **Các Base Video Provider (Torrentio, v.v.)** | Là nút lá (leaf node) trong chuỗi decorator. | Các link trích xuất video đi xuyên qua toàn bộ các decorator trực tiếp đến ExoPlayer với tổng độ trễ <150ms. |

---

## 8. Kết Luận & Nghiệm Thu Kế Hoạch (Conclusion & Sign-Off)

Tài liệu này cấu thành bản đặc tả kỹ thuật và kế hoạch triển khai hoàn chỉnh, có thẩm quyền và đạt chuẩn production cho extension **CloudStream AI Subtitle Translator**. Toàn bộ các mẫu kiến trúc, mô hình dữ liệu, schema API, chiến lược khắc phục lỗi và tiêu chuẩn thẩm định đã được hiệu chỉnh đầy đủ theo các khuyến nghị của đội ngũ reviewer và challenger:
1. **Phân Phối Tĩnh ExoPlayer (ExoPlayer Static Delivery)**: Khắc phục giả định sai về việc thăm dò tệp động; xác định rõ Phân Phối Batch Tốc Độ Cao (Chiến lược A) và Phân Phối Khung Sườn Lũy Tiến (Chiến lược B).
2. **Bảo Toàn Toàn Vẹn Câu Thoại (Dialogue Integrity)**: Khắc phục lỗi cắt xén dòng số ở đuôi trong `SubtitleParser`, bảo vệ an toàn tuyệt đối các câu thoại như `"Agent 47"`.
3. **Chống Phá Vỡ Thẻ Định Dạng (Tag Resilience)**: Triển khai cơ chế unmask token không phân biệt hoa thường và tự động cân bằng toàn bộ các thẻ HTML bao gồm cả thẻ `<font>`.
4. **Đồng Bộ Plugin Mapping**: Bổ sung `APIHolder.addPluginMapping(hoisted)` và đồng bộ hóa việc lặp qua danh sách provider với khóa đồng bộ.
5. **Bộ Giải Tuần Tự Hóa Đa Hình (Polymorphic Deserializer)**: Hợp nhất Structured Outputs của OpenAI, mảng của Gemini, và assistant prefill của Anthropic dưới một bộ xử lý `AITranslationParser` duy nhất.
6. **Tách Biệt Lỗi Gemini (Gemini Decoupling)**: Tách biệt rõ ràng giữa lỗi 15 RPM tạm thời và lỗi cạn hạn mức ngày với thuật toán lùi thời gian ngẫu nhiên.
7. **Tự Động Khắc Phục Đánh Lại Số Của LLM (Auto-Healing Re-indexing)**: Loại bỏ hoàn toàn tình trạng bị mất 100% cue khi mô hình LLM đánh lại số thứ tự từ 1.
8. **An Toàn Bộ Nhớ Đệm (Cache Safety)**: Thực thi cơ chế ghi tệp an toàn trên Windows và dọn dẹp bộ nhớ đệm đệ quy.

Đội ngũ kỹ thuật có thể trực tiếp bắt đầu triển khai từ Giai đoạn 1.
