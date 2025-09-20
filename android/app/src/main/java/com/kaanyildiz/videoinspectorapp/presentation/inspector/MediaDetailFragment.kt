// app/src/main/java/com/kaanyildiz/videoinspectorapp/presentation/inspector/MediaDetailFragment.kt
package com.kaanyildiz.videoinspectorapp.presentation.inspector

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.kaanyildiz.videoinspectorapp.R
import com.kaanyildiz.videoinspectorapp.data.remote.ApiService
import com.kaanyildiz.videoinspectorapp.data.remote.model.CommentRequest
import com.kaanyildiz.videoinspectorapp.data.remote.model.ValidateRequest
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MediaDetailFragment : Fragment() {

    @Inject lateinit var api: ApiService
    @Inject lateinit var tokenStore: TokenStore

    companion object {
        private const val ARG_ID = "id"
        fun new(id: Long) = MediaDetailFragment().apply {
            arguments = Bundle().apply { putLong(ARG_ID, id) }
        }
    }

    private val mediaId: Long by lazy { requireArguments().getLong(ARG_ID) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        return inflater.inflate(R.layout.fragment_media_detail, container, false)
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        v.findViewById<View>(R.id.btnApprove).setOnClickListener {
            setButtonsEnabled(v, false)
            viewLifecycleOwner.lifecycleScope.launch {
                val token = tokenStore.token()
                if (token.isNullOrBlank()) {
                    toast("Oturum bulunamadı."); setButtonsEnabled(v, true); return@launch
                }
                val res = runCatching {
                    api.validate("Bearer $token", mediaId, ValidateRequest(status = "valid"))
                }.getOrNull()

                if (res?.isSuccessful == true) {
                    toast("Onaylandı")
                    parentFragmentManager.setFragmentResult("detail_result", Bundle().apply {
                        putBoolean("refresh", true)
                    })
                    parentFragmentManager.popBackStack()
                } else toast("Onay Hatası ${res?.code() ?: "⚠️"}")
                setButtonsEnabled(v, true)
            }
        }

        v.findViewById<View>(R.id.btnReject).setOnClickListener {
            val commentText = v.findViewById<EditText>(R.id.etComment).text?.toString().orEmpty()
            if (commentText.isBlank()) {
                toast("Sıkıntılı işaretlerken lütfen yorum yazın."); return@setOnClickListener
            }
            setButtonsEnabled(v, false)
            viewLifecycleOwner.lifecycleScope.launch {
                val token = tokenStore.token()
                if (token.isNullOrBlank()) {
                    toast("Oturum bulunamadı."); setButtonsEnabled(v, true); return@launch
                }
                val res = runCatching {
                    api.validate("Bearer $token", mediaId, ValidateRequest(status = "not_valid"))
                }.getOrNull()

                if (res?.isSuccessful == true) {
                    toast("Sıkıntılı işaretlendi")
                    parentFragmentManager.setFragmentResult("detail_result", Bundle().apply {
                        putBoolean("refresh", true)
                    })
                    parentFragmentManager.popBackStack()
                } else toast("Red Hatası ${res?.code() ?: "⚠️"}")
                setButtonsEnabled(v, true)
            }
        }

        v.findViewById<View>(R.id.btnComment).setOnClickListener {
            val txt = v.findViewById<EditText>(R.id.etComment).text?.toString().orEmpty()
            if (txt.isBlank()) { toast("Yorum boş olamaz."); return@setOnClickListener }

            setButtonsEnabled(v, false)
            viewLifecycleOwner.lifecycleScope.launch {
                val token = tokenStore.token()
                if (token.isNullOrBlank()) {
                    toast("Oturum bulunamadı."); setButtonsEnabled(v, true); return@launch
                }
                val res = runCatching {
                    api.comment("Bearer $token", mediaId, CommentRequest(comment = txt))
                }.getOrNull()

                if (res?.isSuccessful == true) toast("Yorum kaydedildi")
                else toast("Yorum Hatası ${res?.code() ?: "⚠️"}")
                setButtonsEnabled(v, true)
            }
        }
    }

    private fun setButtonsEnabled(root: View, enabled: Boolean) {
        root.findViewById<View>(R.id.btnApprove).isEnabled = enabled
        root.findViewById<View>(R.id.btnReject).isEnabled = enabled
        root.findViewById<View>(R.id.btnComment).isEnabled = enabled
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
