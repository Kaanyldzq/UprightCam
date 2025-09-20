// app/src/main/java/com/kaanyildiz/videoinspectorapp/presentation/channels/ChannelListFragment.kt
package com.kaanyildiz.videoinspectorapp.presentation.channels

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.kaanyildiz.videoinspectorapp.core.LogoutManager
import com.kaanyildiz.videoinspectorapp.core.NetworkMonitor
import com.kaanyildiz.videoinspectorapp.data.remote.ApiService
import com.kaanyildiz.videoinspectorapp.databinding.FragmentChannelListBinding
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ChannelListFragment : Fragment() {

    private var _binding: FragmentChannelListBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var api: ApiService
    @Inject lateinit var logoutManager: LogoutManager

    private lateinit var adapter: ChannelAdapter
    private lateinit var networkMonitor: NetworkMonitor

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChannelListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("ChannelList", "onViewCreated")

        networkMonitor = NetworkMonitor(requireContext())

        // Recycler
        adapter = ChannelAdapter { channel ->
            // item tıklama işlemleri…
        }
        binding.recycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            addItemDecoration(
                DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
            )
            adapter = this@ChannelListFragment.adapter
        }

        // Online/Offline'a göre Logout butonu
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkMonitor.isOnlineFlow.collect { online ->
                    binding.btnLogout.isEnabled = online
                }
            }
        }

        // LOGOUT — token yoksa bile başarılı say (merkezî LogoutManager ile)
        binding.btnLogout.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.btnLogout.isEnabled = false
                val online = networkMonitor.isOnlineFlow.first()
                logoutManager.logoutSilently(online)
                requireActivity().finish()
            }
        }

        // Kanalları çek
        fetchChannels()
        binding.root.setBackgroundColor(0x5500FF00.toInt()) // yarı saydam yeşil
    }

    private fun fetchChannels() {
        binding.progress.visibility = View.VISIBLE
        binding.tvError.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val online = networkMonitor.isOnlineFlow.first()
            if (!online) {
                showError("Çevrimdışı: Kanal listesi alınamadı.")
                return@launch
            }

            val token = tokenStore.token()
            if (token.isNullOrBlank()) {
                showError("Oturum bulunamadı. Lütfen tekrar giriş yapın.")
                logoutManager.logoutSilently(false)
                requireActivity().finish()
                return@launch
            }

            try {
                // 🔑 Authorization header'ını ilet
                val response = api.getChannels("Bearer $token")
                if (!response.isSuccessful) {
                    showError("Kanal listesi alınamadı: ${response.code()}")
                    return@launch
                }

                val body = response.body()
                if (body == null) {
                    showError("Sunucu boş yanıt döndürdü.")
                    return@launch
                }

                val list = body.data.map { dto ->
                    Channel(id = dto.id, name = dto.name, status = dto.status)
                }

                adapter.submitList(list)
                binding.progress.visibility = View.GONE
            } catch (e: Exception) {
                e.printStackTrace()
                showError("Kanal listesi alınamadı. ${e.message ?: ""}")
            }
        }
    }

    private fun showError(msg: String) {
        binding.progress.visibility = View.GONE
        binding.tvError.text = msg
        binding.tvError.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
