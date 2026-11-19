package com.example

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.LibraryViewModel
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.SavesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.ConsoleSurface
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.ElectricLimeContainer
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.PocketBoyTheme
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

object PocketBoyRoutes {
    const val ROUTE_LIBRARY = "library"
    const val ROUTE_SAVES = "saves"
    const val ROUTE_SETTINGS = "settings"
    const val ROUTE_PLAYER = "player?romUri={romUri}&slotIndex={slotIndex}"

    fun playerRoute(romUri: String, slotIndex: Int = -1): String {
        return "player?romUri=${Uri.encode(romUri)}&slotIndex=$slotIndex"
    }
}

private data class TopTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val TOP_TABS = listOf(
    TopTab(
        route = PocketBoyRoutes.ROUTE_LIBRARY,
        labelRes = R.string.nav_library,
        selectedIcon = Icons.Filled.SportsEsports,
        unselectedIcon = Icons.Outlined.SportsEsports
    ),
    TopTab(
        route = PocketBoyRoutes.ROUTE_SAVES,
        labelRes = R.string.nav_saves,
        selectedIcon = Icons.Filled.Bookmark,
        unselectedIcon = Icons.Outlined.BookmarkBorder
    ),
    TopTab(
        route = PocketBoyRoutes.ROUTE_SETTINGS,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    )
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketBoyTheme {
                PocketBoyApp()
            }
        }
    }
}

@Composable
fun PocketBoyApp(libraryViewModel: LibraryViewModel = viewModel()) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val saveSlots by libraryViewModel.allSaveSlots.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        libraryViewModel.messages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            libraryViewModel.linkFolderAndScan(treeUri)
        }
    }

    val singleRomPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            libraryViewModel.importSingleRom(uri)
        }
    }

    val isPlayerRoute = currentRoute?.startsWith("player") == true

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp && !isPlayerRoute

        Scaffold(
            containerColor = ObsidianBg,
            contentWindowInsets = if (isPlayerRoute) WindowInsets(0, 0, 0, 0) else WindowInsets.systemBars,
            bottomBar = {
                if (!isPlayerRoute && !useNavRail) {
                    NavigationBar(containerColor = ConsoleSurface) {
                        TOP_TABS.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = ElectricLime,
                                    selectedTextColor = ElectricLime,
                                    indicatorColor = ElectricLimeContainer,
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(containerColor = ConsoleSurface) {
                        TOP_TABS.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = ElectricLime,
                                    selectedTextColor = TextPrimary,
                                    indicatorColor = ElectricLimeContainer,
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted
                                )
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = PocketBoyRoutes.ROUTE_LIBRARY
                    ) {
                        composable(PocketBoyRoutes.ROUTE_LIBRARY) {
                            LibraryScreen(
                                uiState = libraryState,
                                onSelectFolder = { folderPickerLauncher.launch(null) },
                                onRefreshFolder = { libraryViewModel.refreshFolder() },
                                onImportSingleRom = { singleRomPickerLauncher.launch(arrayOf("*/*")) },
                                onSearchQueryChange = libraryViewModel::setSearchQuery,
                                onFilterChange = libraryViewModel::setFilter,
                                onPlayRom = { rom ->
                                    navController.navigate(PocketBoyRoutes.playerRoute(rom.uriString))
                                },
                                onToggleFavorite = libraryViewModel::toggleFavorite,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        composable(PocketBoyRoutes.ROUTE_SAVES) {
                            SavesScreen(
                                saveSlots = saveSlots,
                                onResumeSlot = { slot ->
                                    navController.navigate(
                                        PocketBoyRoutes.playerRoute(slot.romUriString, slot.slotIndex)
                                    )
                                },
                                onDeleteSlot = libraryViewModel::deleteSaveSlot,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        composable(PocketBoyRoutes.ROUTE_SETTINGS) {
                            SettingsScreen(
                                settings = libraryState.settings,
                                onSelectFolder = { folderPickerLauncher.launch(null) },
                                onSoundChanged = libraryViewModel::setSoundEnabled,
                                onHapticChanged = libraryViewModel::setHapticEnabled,
                                onFastForwardChanged = libraryViewModel::setFastForwardMultiplier,
                                onGbPaletteChanged = libraryViewModel::setGbPalettePreset,
                                onPixelFilterChanged = libraryViewModel::setPixelFilter,
                                onShowFpsChanged = libraryViewModel::setShowFps,
                                onOpacityChanged = libraryViewModel::setControllerOpacity,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        composable(
                            route = PocketBoyRoutes.ROUTE_PLAYER,
                            arguments = listOf(
                                navArgument("romUri") { type = NavType.StringType },
                                navArgument("slotIndex") {
                                    type = NavType.IntType
                                    defaultValue = -1
                                }
                            )
                        ) { entry ->
                            val rawUri = entry.arguments?.getString("romUri") ?: ""
                            val decodedUri = Uri.decode(rawUri)
                            val slotIdx = entry.arguments?.getInt("slotIndex") ?: -1
                            PlayerScreen(
                                romUriString = decodedUri,
                                initialSlotIndex = slotIdx,
                                onExit = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
