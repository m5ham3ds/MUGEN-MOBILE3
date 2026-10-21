package com.example.ui.screens.gameDetails

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.MugenApplication
import com.example.data.model.ContentEntity
import com.example.ui.viewmodels.GameDetailsViewModel
import com.example.ui.viewmodels.GameDetailsViewModelFactory
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailsScreen(
    encodedGamePath: String,
    onNavigateBack: () -> Unit,
    onLaunchGame: (String) -> Unit
) {
    val decodedPath = try {
        URLDecoder.decode(encodedGamePath, StandardCharsets.UTF_8.toString())
    } catch (e: Exception) {
        encodedGamePath
    }

    val context = LocalContext.current
    val application = context.applicationContext as MugenApplication
    val viewModel: GameDetailsViewModel = viewModel(
        factory = GameDetailsViewModelFactory(
            application.repository,
            application.contentRepository,
            decodedPath
        )
    )

    val game by viewModel.game.collectAsState()
    val characters by viewModel.characters.collectAsState()
    val stages by viewModel.stages.collectAsState()
    val mods by viewModel.mods.collectAsState()

    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Characters", "Stages", "Mods")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(game?.title ?: "Loading...") },
                navigationIcon = {
                    Button(onClick = onNavigateBack, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Back")
                    }
                },
                actions = {
                    Button(onClick = { 
                        com.example.utils.CrashReporter.recordBreadcrumb("PLAY_BUTTON_CLICKED", "Custom game launch requested: $decodedPath")
                        onLaunchGame(encodedGamePath) 
                    }, modifier = Modifier.padding(end = 8.dp)) {
                        Text("Play")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTabIndex) {
                0 -> ContentList(contentList = characters, onToggle = { viewModel.toggleContent(it) })
                1 -> ContentList(contentList = stages, onToggle = { viewModel.toggleContent(it) })
                2 -> ContentList(contentList = mods, onToggle = { viewModel.toggleContent(it) })
            }
        }
    }
}

@Composable
fun ContentList(contentList: List<ContentEntity>, onToggle: (ContentEntity) -> Unit) {
    if (contentList.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No content found.", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(contentList) { content ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = content.name, style = MaterialTheme.typography.titleMedium)
                        Switch(
                            checked = content.isEnabled,
                            onCheckedChange = { onToggle(content) }
                        )
                    }
                }
            }
        }
    }
}
