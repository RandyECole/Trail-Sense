package com.kylecorry.trail_sense.shared.grouping.picker

import android.content.Context
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.databinding.ViewGroupableSelectorBinding
import com.kylecorry.trail_sense.shared.extensions.setOnQueryTextListener
import com.kylecorry.trail_sense.shared.grouping.Groupable
import com.kylecorry.trail_sense.shared.lists.GroupListManager
import com.kylecorry.trail_sense.shared.lists.ListItemMapper

class GroupableSelectView<T : Groupable>(
    context: Context,
    attrs: AttributeSet?,
    private val manager: GroupListManager<T>,
    private val mapper: ListItemMapper<T>,
    private val titleProvider: (T?) -> String,
    emptyText: String,
    initialGroup: Long? = null,
    searchEnabled: Boolean = true
) : ConstraintLayout(context, attrs) {

    private val binding: ViewGroupableSelectorBinding

    var onItemClick: (item: T) -> Unit = {}
    var root: T? = null
        private set

    init {
        inflate(context, R.layout.view_groupable_selector, this)
        binding = ViewGroupableSelectorBinding.bind(this)

        // Search
        binding.searchbox.isVisible = searchEnabled
        binding.searchbox.setOnQueryTextListener { _, _ ->
            manager.search(binding.searchbox.query)
            true
        }

        // List items
        binding.list.emptyView = binding.emptyText
        binding.emptyText.text = emptyText
        manager.onChange = { root, items, rootChanged ->
            this.root = root
            binding.groupTitle.leftQuickAction.isVisible = root != null
            binding.groupTitle.title.text = titleProvider(root)
            val mapped = items.map {
                // TODO: Don't override anything, just intercept the click action to open folders (let the caller decide what to do on click)
                mapper.map(it).copy(menu = emptyList(), trailingIcon = null, action = {
                    if (it.isGroup) {
                        manager.open(it.id)
                    }
                    onItemClick(it)
                })
            }
            binding.list.setItems(mapped)
            if (rootChanged) {
                binding.list.scrollToPosition(0, false)
            }
        }

        // Back
        binding.groupTitle.leftQuickAction.isVisible = false
        binding.groupTitle.leftQuickAction.setOnClickListener {
            manager.up()
        }

        manager.open(initialGroup)
    }
}