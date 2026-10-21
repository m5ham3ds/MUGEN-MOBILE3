package com.example.ui.navigation

import android.content.Intent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.main.MainScreen
import com.example.ui.screens.splash.SplashScreen
import com.example.ui.screens.runtime.MugenGameActivity
import com.example.ui.screens.gameDetails.GameDetailsScreen
import com.example.ui.screens.controllerEditor.ControllerEditorScreen
import com.example.ui.screens.onboarding.OnboardingScreen
import com.example.data.settings.SettingsDataStore
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
fun AppNavigation(isOnboardingCompleted: Boolean) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val startDest = if (isOnboardingCompleted) "splash" else "onboarding"
    
    NavHost(
        navController = navController, 
        startDestination = startDest,
        enterTransition = {
            androidx.compose.animation.slideInHorizontally(
                initialOffsetX = { 500 },
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 400f)
            ) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300))
        },
        exitTransition = {
            androidx.compose.animation.slideOutHorizontally(
                targetOffsetX = { -500 },
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 400f)
            ) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300))
        },
        popEnterTransition = {
            androidx.compose.animation.slideInHorizontally(
                initialOffsetX = { -500 },
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 400f)
            ) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300))
        },
        popExitTransition = {
            androidx.compose.animation.slideOutHorizontally(
                targetOffsetX = { 500 },
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 400f)
            ) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300))
        }
    ) {
        composable("onboarding") {
            OnboardingScreen(onComplete = {
                scope.launch {
                    SettingsDataStore.setOnboardingCompleted(context, true)
                    navController.navigate("splash") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            })
        }
        composable("splash") {
            SplashScreen(onNavigateToHome = {
                navController.navigate("main") {
                    popUpTo("splash") { inclusive = true }
                }
            })
        }
        composable("main") {
            MainScreen(
                onLaunchBuiltInGame = {
                    val builtInPath = URLEncoder.encode("BUILT_IN_ENGINE", StandardCharsets.UTF_8.toString())
                    navController.navigate("game/$builtInPath")
                },
                onLaunchCustomGame = { gamePath ->
                    navController.navigate("gameDetails/$gamePath")
                },
                onNavigateToControllerEditor = {
                    navController.navigate("controllerEditor")
                },
                onNavigateToDiagnostics = {
                    navController.navigate("diagnostics")
                }
            )
        }
        composable("gameDetails/{gamePath}") { backStackEntry ->
            val gamePath = backStackEntry.arguments?.getString("gamePath") ?: ""
            GameDetailsScreen(
                encodedGamePath = gamePath,
                onNavigateBack = { navController.popBackStack() },
                onLaunchGame = { path ->
                    navController.navigate("game/$path")
                }
            )
        }
        composable("game/{gamePath}") { backStackEntry ->
            val gamePath = backStackEntry.arguments?.getString("gamePath") ?: ""
            LaunchedEffect(gamePath) {
                val intent = Intent(context, MugenGameActivity::class.java).apply {
                    putExtra("gamePath", gamePath)
                }
                context.startActivity(intent)
                navController.popBackStack()
            }
        }
        composable("controllerEditor") {
            ControllerEditorScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable("diagnostics") {
            com.example.ui.screens.diagnostics.DiagnosticsScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
