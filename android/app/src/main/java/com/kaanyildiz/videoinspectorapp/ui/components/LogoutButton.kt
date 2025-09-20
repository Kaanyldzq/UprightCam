package com.kaanyildiz.videoinspectorapp.ui.components

import android.widget.Toast
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaanyildiz.videoinspectorapp.core.NetworkMonitor
import kotlinx.coroutines.launch

@Composable
fun LogoutButton(
    networkMonitor: NetworkMonitor,
    onLogout: suspend () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isOnline by networkMonitor.isOnlineFlow
        .collectAsStateWithLifecycle(
            initialValue = false,
            lifecycleOwner = lifecycleOwner
        )

    Button(
        onClick = {
            scope.launch {
                if (!isOnline) {
                    Toast.makeText(context, "Çevrimdışı: Logout devre dışı", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                onLogout()
            }
        },
        enabled = isOnline
    ) {
        Text("Logout")
    }
}
