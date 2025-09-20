// app/src/main/java/com/kaanyildiz/videoinspectorapp/MainActivity.kt
package com.kaanyildiz.videoinspectorapp

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kaanyildiz.videoinspectorapp.core.hasNetworkConnection
import com.kaanyildiz.videoinspectorapp.data.remote.ApiService
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import com.kaanyildiz.videoinspectorapp.presentation.capture.CaptureFragment
import com.kaanyildiz.videoinspectorapp.presentation.inspector.InspectorListFragment
import com.kaanyildiz.videoinspectorapp.worker.UploadWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var api: ApiService

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Açılışta rol’e göre fragment (sadece ilk açılış)
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                val role = (tokenStore.role() ?: "streamer").trim().lowercase()
                val fragment: Fragment = if (role == "inspector") {
                    InspectorListFragment()
                } else {
                    CaptureFragment()
                }
                supportFragmentManager.beginTransaction()
                    .replace(R.id.mainContainer, fragment)
                    .commit()
            }
        }

        // Logout
        val btnLogout = findViewById<Button>(R.id.btnLogout)
        btnLogout.isEnabled = applicationContext.hasNetworkConnection()
        btnLogout.alpha = if (btnLogout.isEnabled) 1f else 0.5f

        btnLogout.setOnClickListener {
            btnLogout.isEnabled = false
            btnLogout.alpha = 0.5f

            lifecycleScope.launch {
                try {
                    if (applicationContext.hasNetworkConnection()) {
                        val token = tokenStore.token()
                        if (!token.isNullOrBlank()) {
                            // 🔑 Authorization header'ını gönder
                            runCatching { api.logout("Bearer $token") }
                        }
                    }
                } finally {
                    // Her durumda yerel çıkış
                    tokenStore.clear()

                    startActivity(
                        Intent(this@MainActivity, LoginActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    )
                    finish()
                }
            }
        }

        // Arka plan yükleyici
        scheduleUploader()
    }

    override fun onStart() {
        super.onStart()
        val cm = getSystemService(ConnectivityManager::class.java)
        val btnLogout = findViewById<Button>(R.id.btnLogout)

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { btnLogout.isEnabled = true; btnLogout.alpha = 1f }
            }
            override fun onLost(network: Network) {
                runOnUiThread { btnLogout.isEnabled = false; btnLogout.alpha = 0.5f }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val hasNet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                runOnUiThread {
                    btnLogout.isEnabled = hasNet
                    btnLogout.alpha = if (hasNet) 1f else 0.5f
                }
            }
        }
        cm.registerDefaultNetworkCallback(networkCallback!!)
    }

    override fun onStop() {
        super.onStop()
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { cm.unregisterNetworkCallback(it) }
        networkCallback = null
    }

    private fun scheduleUploader() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodic = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "upload_worker",
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )

        val once = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "upload_once",
            ExistingWorkPolicy.REPLACE,
            once
        )
    }
}
