package com.example.ui.screens.main

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.storage.MugenMobileStorage
import com.example.ui.screens.characters.CharactersScreen
import com.example.ui.screens.games.GamesScreen
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.stages.StagesScreen
import com.example.ui.theme.LocalAppStrings
import com.example.utils.PermissionUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onLaunchBuiltInGame: () -> Unit,
    onLaunchCustomGame: (String) -> Unit,
    onNavigateToControllerEditor: () -> Unit,
    onNavigateToDiagnostics: () -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val strings = LocalAppStrings.current

    var hasPermission by remember { mutableStateOf(PermissionUtils.hasStoragePermission(context)) }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasPermission = PermissionUtils.hasStoragePermission(context)
        if (hasPermission) {
            MugenMobileStorage.initializeDirectories()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = PermissionUtils.hasStoragePermission(context)
        if (hasPermission) {
            MugenMobileStorage.initializeDirectories()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                PermissionUtils.requestManageStorageIntent(context)?.let {
                    manageStorageLauncher.launch(it)
                }
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        } else {
            MugenMobileStorage.initializeDirectories()
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "home"
    
    val currentTitle = when (currentRoute) {
        "home" -> strings.play
        "characters" -> strings.characters
        "stages" -> strings.stages
        "games" -> strings.games
        "settings" -> strings.settings
        else -> strings.mugenMobile
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    ModalDrawerSheet(
                        modifier = Modifier.fillMaxWidth(0.75f)
                    ) {
                        Spacer(modifier = Modifier.padding(top = 24.dp))
                        Text(
                            strings.mugenMobile,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                        val items = listOf(
                            Triple("home", strings.play, Icons.Default.PlayArrow),
                            Triple("characters", strings.characters, Icons.Default.Groups),
                            Triple("stages", strings.stages, Icons.Default.Map),
                            Triple("games", strings.games, Icons.Default.Games),
                            Triple("settings", strings.settings, Icons.Default.Settings)
                        )
                        items.forEach { (route, label, icon) ->
                            NavigationDrawerItem(
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                                selected = currentRoute == route,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    navController.navigate(route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text(currentTitle) },
                            actions = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(androidx.compose.material.icons.Icons.Default.Menu, contentDescription = "Menu")
                                }
                            },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            val items = listOf(
                                Triple("home", strings.play, Icons.Default.PlayArrow),
                                Triple("characters", strings.characters, Icons.Default.Groups),
                                Triple("stages", strings.stages, Icons.Default.Map),
                                Triple("games", strings.games, Icons.Default.Games),
                                Triple("settings", strings.settings, Icons.Default.Settings)
                            )
                            items.forEach { (route, label, icon) ->
                                val selected = currentRoute == route
                                val scale by animateFloatAsState(
                                    targetValue = if (selected) 1.35f else 1.0f,
                                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 300f),
                                    label = "iconScale"
                                )
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            icon,
                                            contentDescription = label,
                                            modifier = Modifier.graphicsLayer {
                                                scaleX = scale
                                                scaleY = scale
                                            }
                                        )
                                    },
                                    label = { 
                                        Text(
                                            text = label, 
                                            fontSize = 10.sp, 
                                            maxLines = 1, 
                                            overflow = TextOverflow.Ellipsis
                                        ) 
                                    },
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(route) {
                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.padding(innerPadding),
                        enterTransition = {
                            androidx.compose.animation.slideInHorizontally(
                                initialOffsetX = { 300 },
                                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 400f)
                            ) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300))
                        },
                        exitTransition = {
                            androidx.compose.animation.slideOutHorizontally(
                                targetOffsetX = { -300 },
                                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 400f)
                            ) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300))
                        }
                    ) {
                        composable("home") {
                            HomeScreen(
                                hasPermission = hasPermission,
                                onLaunchBuiltInGame = onLaunchBuiltInGame,
                                onNavigateToDiagnostics = onNavigateToDiagnostics
                            )
                        }
                        composable("characters") {
                            CharactersScreen(hasPermission = hasPermission)
                        }
                        composable("stages") {
                            StagesScreen(hasPermission = hasPermission)
                        }
                        composable("games") {
                            GamesScreen(
                                hasPermission = hasPermission,
                                onLaunchCustomGame = onLaunchCustomGame
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onNavigateToControllerEditor = onNavigateToControllerEditor,
                                onNavigateToDiagnostics = onNavigateToDiagnostics,
                                onNavigateBack = {}
                            )
                        }
                    }
                }
            }
        }
    }
}
