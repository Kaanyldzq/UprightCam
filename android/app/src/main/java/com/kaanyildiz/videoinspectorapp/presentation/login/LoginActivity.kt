// app/src/main/java/com/kaanyildiz/videoinspectorapp/LoginActivity.kt
package com.kaanyildiz.videoinspectorapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kaanyildiz.videoinspectorapp.data.remote.ApiService
import com.kaanyildiz.videoinspectorapp.data.remote.model.LoginRequest
import com.kaanyildiz.videoinspectorapp.data.remote.model.LoginResponse
import com.kaanyildiz.videoinspectorapp.data.remote.model.RoleResponse
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import com.kaanyildiz.videoinspectorapp.presentation.inspector.InspectorMainActivity
import com.kaanyildiz.videoinspectorapp.presentation.streamer.StreamerMainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {

    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var api: ApiService

    private val TAG = "LGN"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        Log.d(TAG, "onCreate()")

        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val etEmail  = findViewById<EditText>(R.id.etEmail)
        val etPass   = findViewById<EditText>(R.id.etPassword)

        btnLogin.setOnClickListener {
            val email = etEmail.text?.toString()?.trim().orEmpty()
            val password = etPass.text?.toString()?.trim().orEmpty()

            if (email.isEmpty() || password.isEmpty()) {
                toast("Email ve şifre gerekli")
                return@setOnClickListener
            }

            btnLogin.isEnabled = false

            lifecycleScope.launch {
                try {
                    // 1) Login
                    val resp = api.login(LoginRequest(email, password))
                    if (!resp.isSuccessful) {
                        toast("Giriş başarısız: ${resp.code()}")
                        return@launch
                    }

                    val body: LoginResponse = resp.body() ?: run {
                        toast("Sunucu beklenmedik yanıt döndürdü.")
                        return@launch
                    }

                    // 2) Token'ı geçici kaydet (gerekirse interceptor kullanırsın)
                    tokenStore.save(body.token, /*role*/"", /*email*/ body.email ?: "")

                    // 3) Rolü token ile iste (🔑 Authorization header)
                    val roleRes = api.userRole("Bearer ${body.token}")
                    if (!roleRes.isSuccessful) {
                        toast("Rol alınamadı (${roleRes.code()})")
                        return@launch
                    }

                    val roleBody: RoleResponse = roleRes.body() ?: run {
                        toast("Rol yanıtı boş"); return@launch
                    }

                    val fetched = roleBody.role?.trim()?.lowercase().orEmpty()
                    val role = when (fetched) {
                        "streamer"  -> "streamer"
                        "inspector" -> "inspector"
                        else        -> "streamer"
                    }

                    // (Not: İstersen /login'den gelen body.role varsa onu direkt kullanıp bu isteği atlamayı seçebilirsin.)

                    // 4) Doğru role ile tekrar kaydet
                    tokenStore.save(body.token, role, body.email ?: "")

                    // 5) Role'e göre yönlendir
                    val next = if (role == "streamer") {
                        Intent(this@LoginActivity, StreamerMainActivity::class.java)
                    } else {
                        Intent(this@LoginActivity, InspectorMainActivity::class.java)
                    }
                    next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(next)
                    finish()

                } catch (e: Exception) {
                    Log.e(TAG, "Login hata: ${e.message}", e)
                    toast("E-Posta/şifre hatalı veya sunucuya ulaşılamıyor.")
                } finally {
                    if (!isFinishing) btnLogin.isEnabled = true
                }
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
