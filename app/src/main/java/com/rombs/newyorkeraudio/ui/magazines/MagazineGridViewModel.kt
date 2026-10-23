package com.rombs.newyorkeraudio.ui.magazines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rombs.newyorkeraudio.data.repository.NewYorkerRepository
import com.rombs.newyorkeraudio.domain.model.Magazine
import com.rombs.newyorkeraudio.ui.common.ScreenLoader
import kotlinx.coroutines.CoroutineScope

class MagazineGridViewModel(
    repository: NewYorkerRepository,
    loadScope: CoroutineScope? = null,
) : ViewModel() {
    private val loader = ScreenLoader<List<Magazine>>(
        scope = loadScope ?: viewModelScope,
        load = { repository.getMagazines() },
        isEmpty = { it.isEmpty() },
    )
    val state = loader.state

    init { refresh() }

    fun refresh() = loader.refresh()
}
