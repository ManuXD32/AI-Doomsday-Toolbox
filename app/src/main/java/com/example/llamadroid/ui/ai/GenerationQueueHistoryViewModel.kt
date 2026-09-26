package com.example.llamadroid.ui.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.example.llamadroid.service.GenerationQueueRepository

class GenerationQueueHistoryViewModel(application: Application) : AndroidViewModel(application) {
    val repository = GenerationQueueRepository(application)
    val history = Pager(
        PagingConfig(
            pageSize = 50,
            initialLoadSize = 50,
            prefetchDistance = 10,
            enablePlaceholders = false,
            maxSize = 200
        ),
        pagingSourceFactory = repository::historyPagingSource
    ).flow.cachedIn(viewModelScope)
}
