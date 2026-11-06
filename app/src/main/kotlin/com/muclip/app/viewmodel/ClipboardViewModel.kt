package com.muclip.app.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muclip.app.MuClipApplication
import com.muclip.core.HistoryItem
import com.muclip.core.PhraseItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClipboardViewModel : ViewModel() {

    private val _historyItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyItems: StateFlow<List<HistoryItem>> = _historyItems.asStateFlow()

    private val _phraseItems = MutableStateFlow<List<PhraseItem>>(emptyList())
    val phraseItems: StateFlow<List<PhraseItem>> = _phraseItems.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _phraseSearchQuery = MutableStateFlow("")
    val phraseSearchQuery: StateFlow<String> = _phraseSearchQuery.asStateFlow()

    init {
        loadData()
        viewModelScope.launch {
            com.muclip.app.data.SyncEventManager.clipboardReceivedFlow.collect {
                loadData()
            }
        }
        viewModelScope.launch {
            com.muclip.app.data.SyncEventManager.historyChangedFlow.collect {
                loadData()
            }
        }
    }

    fun loadData() {
        if (!MuClipApplication.isCoreInitialized()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val history = MuClipApplication.coreOrNull()?.queryHistory(100) ?: emptyList()
                val phrases = MuClipApplication.coreOrNull()?.queryPhrases() ?: emptyList()
                withContext(Dispatchers.Main) {
                    _historyItems.value = history
                    _phraseItems.value = phrases
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        if (!MuClipApplication.isCoreInitialized()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = if (query.isBlank()) {
                    MuClipApplication.coreOrNull()?.queryHistory(100) ?: emptyList()
                } else {
                    MuClipApplication.coreOrNull()?.searchHistory(query, 50) ?: emptyList()
                }
                withContext(Dispatchers.Main) {
                    _historyItems.value = items
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    fun onPhraseSearchQueryChanged(query: String) {
        _phraseSearchQuery.value = query
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            val clip = ClipData.newPlainText("MuClip", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteHistory(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            MuClipApplication.coreOrNull()?.deleteHistory(id)
            loadData()
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            MuClipApplication.coreOrNull()?.clearHistory()
            loadData()
        }
    }

    fun addHistory(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            MuClipApplication.coreOrNull()?.addHistory(text)
            com.muclip.app.data.SyncEventManager.emitHistoryChanged()
            loadData()
        }
    }

    fun addPhrase(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            MuClipApplication.coreOrNull()?.addPhrase(text)
            loadData()
        }
    }

    fun deletePhrase(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            MuClipApplication.coreOrNull()?.deletePhrase(id)
            loadData()
        }
    }

    fun clearAllPhrases() {
        viewModelScope.launch(Dispatchers.IO) {
            MuClipApplication.coreOrNull()?.clearPhrases()
            loadData()
        }
    }

    fun broadcastToPeers(text: String): Int {
        return MuClipApplication.sync?.broadcastClipboard(text, "text") ?: 0
    }

    fun broadcastToPeersAsync(text: String, onResult: ((Int) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val count = MuClipApplication.sync?.broadcastClipboard(text, "text") ?: 0
            withContext(Dispatchers.Main) {
                onResult?.invoke(count)
            }
        }
    }
}
