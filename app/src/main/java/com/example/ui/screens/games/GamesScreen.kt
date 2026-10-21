package com.example.ui.screens.games

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import com.example.data.model.GameEntity
import com.example.storage.StorageManager
import com.example.ui.viewmodels.GameLibraryViewModel
import com.example.ui.viewmodels.GameLibraryViewModelFactory
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import com.example.ui.utils.bounceClick

@Composable
fun GamesScreen(
    hasPermission: Boolean,
    onLaunchCustomGame: (String) -> Unit
) {
    val context = LocalContext.current
    val application = context.applicationContext as MugenApplication
    val viewModel: GameLibraryViewModel = viewModel(
        factory = GameLibraryViewModelFactory(application.repository, application.contentRepository)
    )
    val games by viewModel.allGames.collectAsState()
    val scope = rememberCoroutineScope()

    val documentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            StorageManager.takePersistableUriPermission(context, it)
            scope.launch {
                val metadata = StorageManager.scanGameDirectory(context, it)
                viewModel.importGame(
                    title = metadata.title,
                    folderPath = it.toString(),
                    characters = metadata.characters,
                    stages = metadata.stages
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (games.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No custom games imported.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { documentTreeLauncher.launch(null) }) {
                    Text("Import Full Game")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(games) { game ->
                    GameCard(game = game, onClick = {
                        com.example.utils.CrashReporter.recordBreadcrumb("PLAY_BUTTON_CLICKED", "Custom game launched: " + game.title)
                        val encodedPath = URLEncoder.encode(game.folderPath, StandardCharsets.UTF_8.toString())
                        onLaunchCustomGame(encodedPath)
                    })
                }
            }
            
            FloatingActionButton(
                onClick = { documentTreeLauncher.launch(null) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Text("+")
            }
        }
    }
}

@Composable
fun GameCard(game: GameEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = game.title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text = "Characters: ${game.characterCount}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "Stages: ${game.stageCount}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
