package com.kaanyildiz.videoinspectorapp.presentation.inspector

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class MediaListFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Şimdilik placeholder içerik gösterelim
        return TextView(requireContext()).apply {
            text = "Media List Screen"
            textSize = 18f
            setPadding(32, 32, 32, 32)
        }
    }
}
