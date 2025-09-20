// app/src/main/java/com/kaanyildiz/videoinspectorapp/presentation/streamer/StreamerMainActivity.kt
package com.kaanyildiz.videoinspectorapp.presentation.streamer

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kaanyildiz.videoinspectorapp.R
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import com.kaanyildiz.videoinspectorapp.data.remote.ApiService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import android.widget.Toast
import android.content.Intent
import com.kaanyildiz.videoinspectorapp.LoginActivity
import com.kaanyildiz.videoinspectorapp.presentation.capture.CaptureFragment
import com.kaanyildiz.videoinspectorapp.presentation.inspector.InspectorMainActivity

@AndroidEntryPoint
class StreamerMainActivity : AppCompatActivity() {

    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var api: ApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1) Doğru layout'u bağla
        setContentView(R.layout.activity_streamer)

        lifecycleScope.launch {
            val role = tokenStore.role()?.lowercase().orEmpty()
            if (role != "streamer") {
                Toast.makeText(this@StreamerMainActivity, "Bu ekran sadece STREAMER içindir.", Toast.LENGTH_SHORT).show()
                // Yanlışlıkla geldiyse Inspector'a gönder
                startActivity(Intent(this@StreamerMainActivity, InspectorMainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })
                finish()
                return@launch
            }
        }

        // 2) İlk fragmenti bir kere ekle
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.mainContainer, CaptureFragment())
                .commit()
        }

        // 3) Logout butonu
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            lifecycleScope.launch {
                try {
                    val token = tokenStore.token()
                    if (!token.isNullOrBlank()) {
                        // 🔑 Authorization header'ını gönder
                        runCatching { api.logout("Bearer $token") }
                    }
                } finally {
                    // Her durumda local logout
                    tokenStore.clear()
                    Toast.makeText(this@StreamerMainActivity, "Çıkış yapıldı", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@StreamerMainActivity, LoginActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    })
                    finish()
                }
            }
        }
    }
}
