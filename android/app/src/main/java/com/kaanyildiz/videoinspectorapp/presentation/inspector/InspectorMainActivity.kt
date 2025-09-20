// app/src/main/java/com/kaanyildiz/videoinspectorapp/presentation/inspector/InspectorMainActivity.kt
package com.kaanyildiz.videoinspectorapp.presentation.inspector

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kaanyildiz.videoinspectorapp.R
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class InspectorMainActivity : AppCompatActivity(R.layout.activity_inspector_main) {

    @Inject lateinit var tokenStore: TokenStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Suspend fonksiyonu coroutine içinde çağır
        lifecycleScope.launch {
            val role = tokenStore.role()?.lowercase()
            if (role != "inspector") {
                finish()
                return@launch
            }

            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, InspectorListFragment())
                    .commit()
            }
        }
    }
}
