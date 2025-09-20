// app/src/main/java/com/kaanyildiz/videoinspectorapp/presentation/inspector/InspectorDetailFragment.kt
package com.kaanyildiz.videoinspectorapp.presentation.inspector

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import coil.load
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import com.kaanyildiz.videoinspectorapp.R
import com.kaanyildiz.videoinspectorapp.data.remote.model.MediaItemDto
import com.kaanyildiz.videoinspectorapp.databinding.FragmentInspectorDetailBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class InspectorDetailFragment : Fragment() {

    private var _binding: FragmentInspectorDetailBinding? = null
    private val binding get() = _binding!!

    private val vm: InspectorDetailViewModel by viewModels()
    private var player: ExoPlayer? = null
    private lateinit var baseUrl: String
    private lateinit var item: MediaItemDto

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInspectorDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)

        baseUrl = getString(R.string.base_url)

        // ---- ARGUMENTS ----
        val id = requireArguments().getLong("id")
        val path = requireArguments().getString("path")!!               // zorunlu
        val thumb = requireArguments().getString("thumbnailUrl")        // opsiyon
        val status = requireArguments().getString("status")             // opsiyon
        val chIdArg = requireArguments().getInt("channelId", 0)

        // ---- DTO'yu TÜM PARAMETRELERLE kur ----
        val derivedType = if (path.endsWith(".mp4", true)) "video" else "image"
        item = MediaItemDto(
            id = id,
            type = derivedType,                         // <-- eklendi
            path = path,
            uploadedByEmail = null,                     // <-- eklendi (arg yoksa null)
            channelId = chIdArg.takeIf { it > 0 },      // <-- eklendi (0 geldiyse null)
            status = status,
            thumbnailUrl = thumb
        )

        binding.tvName.text = path.substringAfterLast('/')

        val full = fullUrl(baseUrl, item.path)
        val isVideo = item.type.equals("video", true) || item.path.endsWith(".mp4", true)

        binding.playerView.isVisible = isVideo
        binding.ivImage.isVisible = !isVideo

        if (isVideo && full != null) {
            player = ExoPlayer.Builder(requireContext()).build().also { p ->
                binding.playerView.player = p
                p.setMediaItem(MediaItem.fromUri(Uri.parse(full)))
                p.prepare()
                p.playWhenReady = false
            }
        } else {
            binding.ivImage.load(fullUrl(baseUrl, item.thumbnailUrl) ?: full) {
                placeholder(R.drawable.ic_media_placeholder)
                error(R.drawable.ic_media_placeholder)
                crossfade(true)
            }
        }

        binding.btnApprove.setOnClickListener { doValidate(true) }
        binding.btnReject.setOnClickListener { doValidate(false) }
        binding.btnSendComment.setOnClickListener {
            val txt = binding.etComment.text?.toString().orEmpty()
            if (txt.isBlank()) {
                Toast.makeText(requireContext(), "Yorum boş olamaz", Toast.LENGTH_SHORT).show()
            } else {
                doComment(txt)
            }
        }
    }

    private fun doValidate(valid: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = vm.validate(item.id, valid)
            Toast.makeText(requireContext(), if (ok) "Güncellendi" else "Hata", Toast.LENGTH_SHORT).show()
            if (ok) parentFragmentManager.popBackStack()
        }
    }

    private fun doComment(text: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = vm.comment(item.id, text)
            Toast.makeText(requireContext(), if (ok) "Yorum gönderildi" else "Hata", Toast.LENGTH_SHORT).show()
            if (ok) binding.etComment.setText("")
        }
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}

// Adapter ile aynı URL yardımcı
private fun fullUrl(base: String, raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val b = base.trimEnd('/')
    val s = raw.replace('\\', '/')
    return when {
        s.startsWith("http", ignoreCase = true) -> s
        s.startsWith("/") -> "$b$s"
        s.contains("/uploads/") -> "$b/files/" + s.substringAfter("/uploads/")
        else -> "$b/$s"
    }
}
