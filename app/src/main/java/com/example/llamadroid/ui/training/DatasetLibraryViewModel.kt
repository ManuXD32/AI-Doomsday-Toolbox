package com.example.llamadroid.ui.training

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.training.CuratedDataset
import com.example.llamadroid.training.DatasetDownloadFile
import com.example.llamadroid.training.DatasetFormat
import com.example.llamadroid.training.DatasetHelpers
import com.example.llamadroid.training.DatasetRepository
import com.example.llamadroid.training.DatasetStatus
import com.example.llamadroid.training.HfDatasetClient
import com.example.llamadroid.training.HfDatasetInfo
import com.example.llamadroid.training.HfDatasetSummary
import com.example.llamadroid.training.HfFileEntry
import com.example.llamadroid.training.HfParquetFile
import com.example.llamadroid.training.HfToken
import com.example.llamadroid.training.TrainingDatasetEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-shot localized message: [res] formatted with [arg] when present. */
data class DatasetNotice(@StringRes val res: Int, val arg: String? = null)

sealed interface HfSearchState {
    data object Idle : HfSearchState
    data object Loading : HfSearchState
    data class Results(val items: List<HfDatasetSummary>) : HfSearchState
    data class Error(val message: String) : HfSearchState
}

sealed interface HfDetailState {
    data object Loading : HfDetailState
    data class Loaded(
        val repo: String,
        val info: HfDatasetInfo?,
        val files: List<HfFileEntry>,
        val parquet: List<HfParquetFile>
    ) : HfDetailState
    data class Error(val message: String) : HfDetailState
}

/**
 * State for the dataset library screen. Everything goes through [DatasetRepository] (the single
 * dataset registry also used by the trainers); this class only adds search state and polling.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class DatasetLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val repository = DatasetRepository(application, db)
    private val dao = db.trainingDatasetDao()
    private val hf = HfDatasetClient(tokenProvider = { HfToken.read(application) })

    val datasets: StateFlow<List<TrainingDatasetEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _notices = MutableSharedFlow<DatasetNotice>(extraBufferCapacity = 4)
    val notices: SharedFlow<DatasetNotice> = _notices.asSharedFlow()

    /** (copiedBytes, totalBytes or -1) of the file import in progress, or null. */
    private val _importProgress = MutableStateFlow<Pair<Long, Long>?>(null)
    val importProgress: StateFlow<Pair<Long, Long>?> = _importProgress.asStateFlow()

    private val _importName = MutableStateFlow("")
    val importName: StateFlow<String> = _importName.asStateFlow()

    val query = MutableStateFlow("")

    val searchState: StateFlow<HfSearchState> = query
        .debounce(450)
        .distinctUntilChanged()
        .flatMapLatest { raw ->
            flow {
                val q = raw.trim()
                if (q.length < 2) {
                    emit(HfSearchState.Idle)
                } else {
                    emit(HfSearchState.Loading)
                    emit(
                        hf.search(q).fold(
                            onSuccess = { HfSearchState.Results(it) },
                            onFailure = { HfSearchState.Error(it.message ?: it.javaClass.simpleName) }
                        )
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HfSearchState.Idle)

    private val _detail = MutableStateFlow<HfDetailState?>(null)
    val detail: StateFlow<HfDetailState?> = _detail.asStateFlow()

    init {
        // Recompute status of downloading rows from their files and download tasks.
        viewModelScope.launch {
            while (true) {
                datasets.value.filter { it.status == DatasetStatus.DOWNLOADING.value || it.status == DatasetStatus.PENDING.value }
                    .forEach { runCatching { repository.refreshStatus(it.id) } }
                delay(2_000)
            }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun openDetail(repo: String) {
        _detail.value = HfDetailState.Loading
        viewModelScope.launch {
            _detail.value = coroutineScope {
                val info = async { hf.info(repo) }
                val tree = async { hf.tree(repo) }
                val parquet = async { hf.parquetFiles(repo) }
                val infoResult = info.await()
                val treeResult = tree.await()
                val parquetResult = parquet.await()
                if (infoResult.isFailure && treeResult.isFailure) {
                    HfDetailState.Error(
                        (infoResult.exceptionOrNull() ?: treeResult.exceptionOrNull())?.message ?: "error"
                    )
                } else {
                    HfDetailState.Loaded(
                        repo = repo,
                        info = infoResult.getOrNull(),
                        files = DatasetUiHelpers.trainableFiles(treeResult.getOrDefault(emptyList())),
                        parquet = parquetResult.getOrDefault(emptyList())
                    )
                }
            }
        }
    }

    fun closeDetail() {
        _detail.value = null
    }

    fun addCurated(entry: CuratedDataset, capMb: Int) {
        viewModelScope.launch {
            repository.addCurated(entry, capMb).fold(
                onSuccess = { _notices.emit(DatasetNotice(R.string.dataset_added_notice, entry.title)) },
                onFailure = { _notices.emit(DatasetNotice(R.string.dataset_add_failed, it.message ?: it.javaClass.simpleName)) }
            )
        }
    }

    /** [files] pairs a Hub path with its size; [revision] is `main` or `refs/convert/parquet`. */
    fun addHf(
        name: String, repo: String, revision: String, licence: String, gated: Boolean,
        format: DatasetFormat, columns: Map<String, String>, files: List<DatasetDownloadFile>,
        config: String?, split: String?, capMb: Int
    ) {
        viewModelScope.launch {
            repository.addHf(
                name = name, repo = repo, revision = revision, licence = licence, gated = gated,
                format = format, columnMap = columns, files = files, config = config, split = split
            ).fold(
                onSuccess = { id ->
                    if (capMb > 0) applyCap(id, capMb, format, files.firstOrNull()?.path)
                    _notices.emit(DatasetNotice(R.string.dataset_added_notice, name))
                    closeDetail()
                },
                onFailure = { _notices.emit(DatasetNotice(R.string.dataset_add_failed, it.message ?: it.javaClass.simpleName)) }
            )
        }
    }

    fun importFile(uri: Uri, displayName: String, format: DatasetFormat, columns: Map<String, String>, capMb: Int) {
        if (_importProgress.value != null) return
        _importName.value = displayName
        _importProgress.value = 0L to -1L
        viewModelScope.launch {
            repository.importFile(uri) { copied, total -> _importProgress.value = copied to total }.fold(
                onSuccess = { id ->
                    val row = dao.getById(id)
                    if (row != null) {
                        dao.update(row.copy(format = format.value, columnMapJson = DatasetHelpers.columnMapJson(columns)))
                        if (capMb > 0) applyCap(id, capMb, format, row.name)
                    }
                    _notices.emit(DatasetNotice(R.string.dataset_import_done, displayName))
                },
                onFailure = { _notices.emit(DatasetNotice(R.string.dataset_import_failed, it.message ?: it.javaClass.simpleName)) }
            )
            _importProgress.value = null
        }
    }

    /** Stores the row estimate for a capped read (the repository only knows the cap for curated rows). */
    private suspend fun applyCap(id: Long, capMb: Int, format: DatasetFormat, fileName: String?) {
        val row = dao.getById(id) ?: return
        val rows = DatasetHelpers.capToRows(capMb, fileName?.let { DatasetHelpers.fileKind(it) }, format) ?: return
        dao.updateSize(id, row.sizeBytes, rows)
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}
