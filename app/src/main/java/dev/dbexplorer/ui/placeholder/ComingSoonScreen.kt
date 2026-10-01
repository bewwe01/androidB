package dev.dbexplorer.ui.placeholder

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.dbexplorer.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComingSoonScreen(title: String, body: String) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { padding ->
        EmptyState(title = "Coming in Phase 2", body = body, modifier = Modifier.padding(padding))
    }
}
