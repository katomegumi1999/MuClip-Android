package com.muclip.app.ui.screens

import androidx.compose.runtime.Composable
import com.muclip.app.viewmodel.ClipboardViewModel

@Composable
fun HistoryScreen(viewModel: ClipboardViewModel) {
    GenericClipListScreen(
        viewModel = viewModel,
        type = ClipListType.CLIPBOARD
    )
}
