package com.tim.gallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tim.gallery.ui.AlbumScreen
import com.tim.gallery.ui.AlbumsScreen
import com.tim.gallery.data.AlbumOps
import com.tim.gallery.edit.EditorScreen
import com.tim.gallery.ui.GalleryTheme
import com.tim.gallery.ui.MainBackground
import com.tim.gallery.ui.SettingsScreen
import com.tim.gallery.personal.PersonalScreen
import com.tim.gallery.ui.TrashScreen
import com.tim.gallery.ui.MainTab
import com.tim.gallery.ui.MainTabsBar
import com.tim.gallery.ui.PermissionGate
import com.tim.gallery.ui.PhotosScreen
import com.tim.gallery.ui.PickerScreen
import com.tim.gallery.ui.VideoPlayerScreen
import com.tim.gallery.ui.ViewerScreen
import com.tim.gallery.ui.ZoomableImage
import com.tim.gallery.ui.decodeKey
import com.tim.gallery.ui.encodeKey

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Opened from another app (Files, Downloads, a chat app...) to view one photo/video
        val externalUri: Uri? = if (intent?.action == Intent.ACTION_VIEW) intent.data else null
        val externalMime: String? = intent?.type ?: externalUri?.let { contentResolver.getType(it) }

        setContent {
            GalleryTheme {
                if (externalUri != null) {
                    ExternalViewer(externalUri, externalMime, onClose = { finish() })
                } else {
                    GalleryRoot()
                }
            }
        }
    }
}

private fun NavHostController.switchTab(tab: MainTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun GalleryRoot() {
    val vm: GalleryViewModel = viewModel()

    PermissionGate(onRefresh = { vm.refresh() }) { access, requestAccess ->
        val nav = rememberNavController()
        // App-wide actions, so "Undo" still works after leaving the screen that did something
        val rootActions = com.tim.gallery.ui.rememberMediaActions(vm)
        Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = MainTab.PHOTOS.route,
            enterTransition = { fadeIn(tween(200)) },
            exitTransition = { fadeOut(tween(200)) },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition = { fadeOut(tween(200)) },
        ) {
            composable(MainTab.PHOTOS.route) {
                MainBackground {
                    PhotosScreen(
                        vm = vm,
                        access = access,
                        onRequestAccess = requestAccess,
                        onOpen = { id -> nav.navigate("viewer/$id") },
                        onOpenSettings = { nav.navigate("settings") },
                        onOpenPersonal = { nav.navigate("personal") },
                        bottomBar = { MainTabsBar(MainTab.PHOTOS) { nav.switchTab(it) } },
                    )
                }
            }
            composable(MainTab.ALBUMS.route) {
                MainBackground {
                    AlbumsScreen(
                        vm = vm,
                        onOpenAlbum = { key -> nav.navigate("album/${encodeKey(key)}") },
                        onOpenTrash = { nav.navigate("trash") },
                        onOpenPersonal = { nav.navigate("personal") },
                        onOpenSettings = { nav.navigate("settings") },
                        bottomBar = { MainTabsBar(MainTab.ALBUMS) { nav.switchTab(it) } },
                    )
                }
            }
            composable("trash") {
                MainBackground {
                    TrashScreen(
                        vm = vm,
                        onBack = { nav.popBackStack() },
                        onOpenItem = { id -> nav.navigate("viewer/$id?album=${encodeKey(AlbumOps.TRASH_KEY)}") },
                    )
                }
            }
            composable("settings") {
                MainBackground {
                    SettingsScreen(onBack = { nav.popBackStack() }, onOpenPersonal = { nav.navigate("personal") })
                }
            }
            composable("personal") {
                PersonalScreen(onBack = { nav.popBackStack() })
            }
            composable("album/{key}") { entry ->
                val key = decodeKey(entry.arguments?.getString("key").orEmpty())
                MainBackground {
                    AlbumScreen(
                        vm = vm,
                        albumKey = key,
                        onBack = { nav.popBackStack() },
                        onOpenItem = { id -> nav.navigate("viewer/$id?album=${encodeKey(key)}") },
                        onAddItems = { nav.navigate("picker/${encodeKey(key)}") },
                        onRenamed = { newKey ->
                            nav.popBackStack()
                            nav.navigate("album/${encodeKey(newKey)}")
                        },
                        onOpenPersonal = { nav.navigate("personal") },
                    )
                }
            }
            composable("picker/{key}") { entry ->
                PickerScreen(
                    vm = vm,
                    targetKey = decodeKey(entry.arguments?.getString("key").orEmpty()),
                    onDone = { nav.popBackStack() },
                )
            }
            composable(
                "viewer/{id}?album={album}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("album") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                ViewerScreen(
                    vm = vm,
                    startId = entry.arguments?.getLong("id") ?: 0L,
                    albumKey = entry.arguments?.getString("album")?.let { decodeKey(it) },
                    onBack = { nav.popBackStack() },
                    onPlay = { id -> nav.navigate("player/$id") },
                    onEdit = { id -> nav.navigate(if (vm.find(id)?.isVideo == true) "videoedit/$id" else "editor/$id") },
                    onOpenPersonal = { nav.navigate("personal") },
                )
            }
            composable(
                "player/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val item = vm.find(entry.arguments?.getLong("id") ?: 0L)
                if (item == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    VideoPlayerScreen(uri = item.uri, title = item.name, onBack = { nav.popBackStack() })
                }
            }
            composable(
                "videoedit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val item = vm.find(entry.arguments?.getLong("id") ?: 0L)
                if (item == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    com.tim.gallery.video.VideoEditScreen(vm = vm, item = item, onClose = { nav.popBackStack() })
                }
            }
            composable(
                "editor/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val item = vm.find(entry.arguments?.getLong("id") ?: 0L)
                if (item == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    EditorScreen(vm = vm, item = item, onClose = { nav.popBackStack() })
                }
            }
        }
            com.tim.gallery.ui.UndoHost(
                actions = rootActions,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 80.dp),
            )
        }
    }
}

@Composable
private fun ExternalViewer(uri: Uri, mime: String?, onClose: () -> Unit) {
    if (mime?.startsWith("video") == true) {
        VideoPlayerScreen(uri = uri, title = uri.lastPathSegment ?: "Video", onBack = onClose)
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ZoomableImage(
                model = uri,
                contentDescription = null,
                isCurrent = true,
                onTap = {},
                onZoomChange = {},
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }
    }
}
