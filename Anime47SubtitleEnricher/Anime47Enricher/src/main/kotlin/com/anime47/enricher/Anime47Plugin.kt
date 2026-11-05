package com.anime47.enricher

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CloudStream Extension Entrypoint for Anime47 Subtitle Enricher.
 */
@CloudstreamPlugin
class Anime47Plugin : Plugin() {

    override fun load(context: Context) {
        // 1. Initialize credentials from SharedPreferences
        Anime47Auth.initFromPreferences(context)

        // 2. Register Anime47 as an independent provider
        registerMainAPI(Anime47Provider())

        // 3. Attach dynamic subtitle enricher hooks to external providers (Torrentio, etc.)
        Anime47Hook.attachHooks()

        // 4. Configure Plugin Settings dialog
        openSettings = { activityContext ->
            showSettingsDialog(activityContext)
        }
    }

    override fun beforeUnload() {
        unload()
    }

    fun unload(context: Context? = null) {
        Anime47Hook.detachHooks()
        Anime47Hook.episodeDataCache.clear()
        Anime47Hook.latestLoadedShow = null
    }

    private fun showSettingsDialog(context: Context) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val descView = TextView(context).apply {
            text = "Tiện ích tự động trích xuất phụ đề Tiếng Việt (softsub) từ Anime47 và nhúng vào Torrentio / player CloudStream.\n\nĐã tích hợp sẵn tài khoản VIP. Bạn có thể đổi tài khoản Anime47 cá nhân dưới đây:"
            textSize = 14f
        }
        layout.addView(descView)

        val emailLabel = TextView(context).apply {
            text = "\nTài khoản / Email Anime47:"
            textSize = 13f
        }
        layout.addView(emailLabel)

        val emailInput = EditText(context).apply {
            hint = "Email hoặc Username Anime47"
            setText(Anime47Auth.email)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        layout.addView(emailInput)

        val passLabel = TextView(context).apply {
            text = "Mật khẩu:"
            textSize = 13f
        }
        layout.addView(passLabel)

        val passInput = EditText(context).apply {
            hint = "Mật khẩu"
            setText(Anime47Auth.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(passInput)

        AlertDialog.Builder(context)
            .setTitle("Cài đặt Anime47 Subtitle Enricher")
            .setView(layout)
            .setPositiveButton("Lưu & Đăng nhập") { dialog, _ ->
                val newEmail = emailInput.text.toString().trim()
                val newPass = passInput.text.toString().trim()

                if (newEmail.isNotBlank() && newPass.isNotBlank()) {
                    Anime47Auth.saveToPreferences(context, newEmail, newPass)
                    Toast.makeText(context, "Đang kiểm tra đăng nhập Anime47...", Toast.LENGTH_SHORT).show()

                    CoroutineScope(Dispatchers.IO).launch {
                        val success = Anime47Auth.attemptLogin()
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(context, "Đăng nhập Anime47 thành công!", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Đăng nhập thất bại! Vui lòng kiểm tra lại tài khoản.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNeutralButton("Khôi phục mặc định") { dialog, _ ->
                Anime47Auth.resetToDefault(context)
                Toast.makeText(context, "Đã khôi phục tài khoản VIP mặc định.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Đóng") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
