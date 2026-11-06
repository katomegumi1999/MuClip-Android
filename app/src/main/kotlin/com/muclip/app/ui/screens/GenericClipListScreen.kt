package com.muclip.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muclip.app.ui.components.LocalMuBottomInset
import com.muclip.app.ui.theme.*
import com.muclip.app.viewmodel.ClipboardViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ClipListType(
    val title: String,
    val searchPlaceholder: String,
    val countUnit: String,
    val emptyText: String,
    val clearDialogTitle: String,
    val clearButtonText: String,
    val addDialogTitle: String,
    val addPlaceholder: String,
    val hasAddFab: Boolean
) {
    CLIPBOARD(
        title = "剪贴板",
        searchPlaceholder = "搜索剪贴板记录...",
        countUnit = "条记录",
        emptyText = "暂无剪贴板历史记录",
        clearDialogTitle = "确认清空剪贴板历史？",
        clearButtonText = "清空历史",
        addDialogTitle = "添加剪贴板记录",
        addPlaceholder = "输入文本内容...",
        hasAddFab = false
    ),
    PHRASES(
        title = "常用语",
        searchPlaceholder = "搜索常用语...",
        countUnit = "条常用语",
        emptyText = "暂无常用短语，点击右下角按钮添加",
        clearDialogTitle = "确认清空所有常用语？",
        clearButtonText = "清空常用语",
        addDialogTitle = "添加常用短语",
        addPlaceholder = "输入常用文本或代码片段...",
        hasAddFab = true
    )
}

data class ClipEntry(
    val id: Long,
    val content: String,
    val createdAt: String,
    val charCount: Int
)

fun formatFullDateTime(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    // If raw already contains full "yyyy-MM-dd HH:mm:ss"
    if (trimmed.length >= 19 && trimmed[4] == '-' && trimmed[7] == '-') {
        return trimmed.take(19)
    }
    // Try parsing numeric epoch milliseconds or seconds
    val epoch = trimmed.toLongOrNull()
    if (epoch != null && epoch > 0) {
        val millis = if (epoch < 100_000_000_000L) epoch * 1000L else epoch
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
    }
    return trimmed
}

@Composable
fun GenericClipListScreen(
    viewModel: ClipboardViewModel,
    type: ClipListType
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var showClearDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newText by remember { mutableStateOf("") }

    val searchQuery: String
    val onSearchChange: (String) -> Unit
    val entries: List<ClipEntry>
    val onClearAll: () -> Unit
    val onDeleteItem: (Long) -> Unit
    val onAddItem: (String) -> Unit

    when (type) {
        ClipListType.CLIPBOARD -> {
            val q by viewModel.searchQuery.collectAsState()
            val history by viewModel.historyItems.collectAsState()
            searchQuery = q
            onSearchChange = { viewModel.onSearchQueryChanged(it) }
            entries = history.map {
                ClipEntry(
                    id = it.id,
                    content = it.content,
                    createdAt = formatFullDateTime(it.createdAt),
                    charCount = it.charCount
                )
            }
            onClearAll = { viewModel.clearAllHistory() }
            onDeleteItem = { viewModel.deleteHistory(it) }
            onAddItem = { viewModel.addHistory(it) }
        }
        ClipListType.PHRASES -> {
            val q by viewModel.phraseSearchQuery.collectAsState()
            val phrases by viewModel.phraseItems.collectAsState()
            searchQuery = q
            onSearchChange = { viewModel.onPhraseSearchQueryChanged(it) }
            val filtered = if (q.isBlank()) phrases else phrases.filter {
                it.content.contains(q, ignoreCase = true)
            }
            entries = filtered.map {
                ClipEntry(
                    id = it.id,
                    content = it.content,
                    createdAt = formatFullDateTime(it.createdAt),
                    charCount = if (it.charCount > 0) it.charCount else it.content.length
                )
            }
            onClearAll = { viewModel.clearAllPhrases() }
            onDeleteItem = { viewModel.deletePhrase(it) }
            onAddItem = { viewModel.addPhrase(it) }
        }
    }

    // Scroll to top on list prepend
    val firstId = entries.firstOrNull()?.id
    LaunchedEffect(firstId) {
        if (firstId != null && entries.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MuClipBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
        ) {
            // 1. Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                placeholder = { Text(type.searchPlaceholder, color = MuClipTextSecondary) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = MuClipPrimary)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = MuClipTextSecondary)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MuClipPrimary,
                    unfocusedBorderColor = MuClipCardBorder,
                    focusedContainerColor = MuClipSurface,
                    unfocusedContainerColor = MuClipSurface,
                    focusedTextColor = MuClipTextPrimary,
                    unfocusedTextColor = MuClipTextPrimary
                )
            )

            // 2. Header Status & Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "共 ${entries.size} ${type.countUnit}",
                    color = MuClipTextSecondary,
                    fontSize = 13.sp
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (type == ClipListType.PHRASES) {
                        TextButton(onClick = { showAddDialog = true }) {
                            Text("添加", color = MuClipPrimary, fontSize = 13.sp)
                        }
                    }

                    if (entries.isNotEmpty()) {
                        TextButton(onClick = { showClearDialog = true }) {
                            Text(type.clearButtonText, color = MuClipRed, fontSize = 13.sp)
                        }
                    }
                }
            }

            // 3. Items List
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isEmpty()) type.emptyText else "未找到匹配项",
                        color = MuClipTextSecondary,
                        fontSize = 15.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = LocalMuBottomInset.current),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(entries, key = { it.id }) { item ->
                        ClipCard(
                            entry = item,
                            onCopy = { viewModel.copyToClipboard(context, item.content) },
                            onSync = {
                                viewModel.broadcastToPeersAsync(item.content) { count ->
                                    val msg = if (count > 0) "已同步至 $count 台设备" else "已触发广播 (无在线设备)"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDelete = { onDeleteItem(item.id) }
                        )
                    }
                }
            }
        }

        // 4. Floating Action Button for Phrases
        if (type.hasAddFab) {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = LocalMuBottomInset.current + 12.dp),
                containerColor = MuClipPrimary,
                contentColor = MuClipOnPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = type.addDialogTitle)
            }
        }
    }

    // Clear Confirmation Dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(type.clearDialogTitle, color = MuClipTextPrimary) },
            text = { Text("清空后数据将无法恢复。", color = MuClipTextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    showClearDialog = false
                }) {
                    Text("清空", color = MuClipRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消", color = MuClipTextSecondary)
                }
            },
            containerColor = MuClipCard
        )
    }

    // Add Item Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(type.addDialogTitle, color = MuClipTextPrimary) },
            text = {
                OutlinedTextField(
                    value = newText,
                    onValueChange = { newText = it },
                    placeholder = { Text(type.addPlaceholder, color = MuClipTextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MuClipPrimary,
                        unfocusedBorderColor = MuClipCardBorder,
                        focusedTextColor = MuClipTextPrimary,
                        unfocusedTextColor = MuClipTextPrimary
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newText.isNotBlank()) {
                        onAddItem(newText.trim())
                        newText = ""
                        showAddDialog = false
                    }
                }) {
                    Text("保存", color = MuClipPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    newText = ""
                    showAddDialog = false
                }) {
                    Text("取消", color = MuClipTextSecondary)
                }
            },
            containerColor = MuClipCard
        )
    }
}

@Composable
fun ClipCard(
    entry: ClipEntry,
    onCopy: () -> Unit,
    onSync: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MuClipCardBorder, RoundedCornerShape(12.dp))
            .clickable { onCopy() },
        colors = CardDefaults.cardColors(containerColor = MuClipCard)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Text Content Preview
            Text(
                text = entry.content,
                color = MuClipTextPrimary,
                fontSize = 15.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Meta Info & Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Info badges: Full Date & Time (年月日 时分秒) + Character Count
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.createdAt.isNotEmpty()) {
                        Text(
                            text = entry.createdAt,
                            color = MuClipTextSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = "${entry.charCount} 字符",
                        color = MuClipTextSecondary,
                        fontSize = 12.sp
                    )
                }

                // Action icons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            tint = MuClipPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(onClick = onSync, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = "广播同步",
                            tint = MuClipGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MuClipTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
