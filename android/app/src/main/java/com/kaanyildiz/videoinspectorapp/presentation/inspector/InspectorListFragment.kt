// app/src/main/java/com/kaanyildiz/videoinspectorapp/presentation/inspector/InspectorListFragment.kt
package com.kaanyildiz.videoinspectorapp.presentation.inspector

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.kaanyildiz.videoinspectorapp.BuildConfig
import com.kaanyildiz.videoinspectorapp.R
import com.kaanyildiz.videoinspectorapp.data.remote.model.MediaItemDto
import com.kaanyildiz.videoinspectorapp.databinding.FragmentInspectorListBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class InspectorListFragment : Fragment() {

    private var _binding: FragmentInspectorListBinding? = null
    private val binding get() = _binding!!

    private val vm: InspectorListViewModel by viewModels()
    private lateinit var adapter: InspectorAdapter
    private lateinit var baseUrl: String

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInspectorListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)

        // Base URL: tek kaynak BuildConfig
        baseUrl = BuildConfig.BASE_URL

        adapter = InspectorAdapter(baseUrl) { openDetail(it) }
        binding.recycler.setHasFixedSize(true)
        binding.recycler.adapter = adapter

        // durum filtresi
        val allLabel = getString(R.string.all)
        val statuses = listOf("waiting", "valid", "not_valid", allLabel)
        binding.spStatus.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            statuses
        )
        binding.spStatus.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val sel = statuses[pos]
                vm.load(status = sel.takeIf { it != allLabel })
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // pull-to-refresh
        binding.swipe.setOnRefreshListener { vm.refresh() }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.state.collectLatest { st ->
                // progress
                binding.progress.isVisible = st.loading && !binding.swipe.isRefreshing
                binding.swipe.isRefreshing = st.loading && binding.swipe.isRefreshing
                // empty
                binding.empty.isVisible = !st.loading && st.items.isEmpty()
                // error
                st.error?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
                // data
                adapter.submitList(st.items)
            }
        }

        if (savedInstanceState == null) vm.load(status = "waiting")
    }

    private fun openDetail(item: MediaItemDto) {
        // MediaItemDto modelinde bu alanlar zorunluysa argümanlara ekle
        val inferredType = if (item.path.endsWith(".mp4", true)) "video" else "photo"

        val args = bundleOf(
            "id" to item.id,
            "path" to item.path,
            "thumbnailUrl" to item.thumbnailUrl,
            "status" to item.status,
            "channelId" to item.channelId,                     // DTO alan adlarınla eşleşmeli
            "type" to (item.type ?: inferredType),
            "uploadedByEmail" to (item.uploadedByEmail ?: "")
        )

        // DİKKAT: R.id.fragmentContainer, aktivite layout’unda var olmalı (aşağıdaki ❸)
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, InspectorDetailFragment().apply { arguments = args })
            .addToBackStack("detail")
            .commit()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
