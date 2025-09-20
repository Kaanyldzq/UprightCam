package com.kaanyildiz.videoinspectorapp.presentation.videos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.kaanyildiz.videoinspectorapp.R
import com.kaanyildiz.videoinspectorapp.data.remote.model.VideoItemDto
import com.kaanyildiz.videoinspectorapp.databinding.FragmentVideoListBinding
import com.kaanyildiz.videoinspectorapp.presentation.inspector.MediaDetailFragment

class VideoListFragment : Fragment() {

    private var _binding: FragmentVideoListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: VideoListViewModel by viewModels()
    private lateinit var adapter: VideoAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1) Liste/adapter kurulumu (değişmedi)
        adapter = VideoAdapter { item: VideoItemDto ->
            parentFragmentManager.beginTransaction()
                .replace(R.id.inspectorContainer, MediaDetailFragment.new(item.id))
                .addToBackStack(null)
                .commit()
        }
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        // 2) Detail'den dönüşte yenilemek için result dinle
        parentFragmentManager.setFragmentResultListener(
            "detail_result",
            viewLifecycleOwner
        ) { _, bundle ->
            val needRefresh = bundle.getBoolean("refresh", false)
            if (needRefresh) {
                binding.progress.visibility = View.VISIBLE
                viewModel.fetchVideos(
                    channels = emptyList(),
                    from = null,
                    to = null,
                    email = null,
                    status = "waiting"
                )
            }
        }

        // 3) İlk açılışta veriyi çek
        binding.progress.visibility = View.VISIBLE
        viewModel.videos.observe(viewLifecycleOwner, Observer { list: List<VideoItemDto> ->
            adapter.submitList(list)
            binding.progress.visibility = View.GONE
        })
        viewModel.fetchVideos(
            channels = emptyList(),
            from = null,
            to = null,
            email = null,
            status = "waiting"
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
