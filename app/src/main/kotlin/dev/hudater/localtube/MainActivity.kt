package dev.hudater.localtube

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.hudater.localtube.core.model.ChannelId
import dev.hudater.localtube.core.model.PlaylistId
import dev.hudater.localtube.ui.SelectionState
import dev.hudater.localtube.ui.channel.ChannelScreen
import dev.hudater.localtube.ui.channel.ChannelViewModel
import dev.hudater.localtube.ui.home.HomeScreen
import dev.hudater.localtube.ui.home.HomeViewModel
import dev.hudater.localtube.ui.player.PlayerScreen
import dev.hudater.localtube.ui.player.PlayerViewModel
import dev.hudater.localtube.ui.playlist.PlaylistScreen
import dev.hudater.localtube.ui.playlist.PlaylistViewModel
import dev.hudater.localtube.ui.settings.SettingsScreen
import dev.hudater.localtube.ui.settings.SettingsViewModel
import dev.hudater.localtube.ui.theme.LocalTubeTheme
import dev.hudater.localtube.work.ScanScheduler

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as LocalTubeApplication).container
        ScanScheduler.schedulePeriodic(applicationContext)

        setContent {
            LocalTubeTheme {
                val navController = rememberNavController()
                val selectionState = remember { SelectionState() }

                // In-memory lookup so the player/channel/playlist screens
                // don't need Video/Channel/Playlist to be Parcelable — the
                // Home screen's already-loaded list is reused instead of
                // re-fetching by id. Acceptable for a personal app with a
                // single Activity; see docs/ARCHITECTURE.md if this ever
                // needs to survive process death mid-navigation.
                val videoLookup = remember { mutableMapOf<String, dev.hudater.localtube.core.model.Video>() }

                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        val viewModel: HomeViewModel = viewModel(
                            factory = HomeViewModel.Factory(
                                applicationContext,
                                container.sourceRepository,
                                container.libraryRepository,
                                selectionState,
                            )
                        )
                        HomeScreen(
                            viewModel = viewModel,
                            selectionState = selectionState,
                            onVideoClick = { video ->
                                videoLookup[video.id.value] = video
                                navController.navigate("player/${video.id.value}")
                            },
                            onChannelClick = { channel ->
                                selectionState.selectChannel(channel.id)
                                navController.navigate("channel/${channel.id.value}/${channel.name}")
                            },
                            onOpenSettings = { navController.navigate("settings") },
                        )
                    }

                    composable(
                        "channel/{channelId}/{channelName}",
                        arguments = listOf(
                            navArgument("channelId") { type = NavType.StringType },
                            navArgument("channelName") { type = NavType.StringType },
                        ),
                    ) { backStackEntry ->
                        val channelId = ChannelId(backStackEntry.arguments?.getString("channelId").orEmpty())
                        val channelName = backStackEntry.arguments?.getString("channelName").orEmpty()
                        val viewModel: ChannelViewModel = viewModel(
                            factory = ChannelViewModel.Factory(
                                channelId,
                                channelName,
                                container.sourceRepository,
                                container.libraryRepository,
                                selectionState,
                            )
                        )
                        ChannelScreen(
                            viewModel = viewModel,
                            onVideoClick = { video ->
                                videoLookup[video.id.value] = video
                                navController.navigate("player/${video.id.value}")
                            },
                            onPlaylistClick = { playlist ->
                                navController.navigate("playlist/${playlist.id.value}/${playlist.name}")
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(
                        "playlist/{playlistId}/{playlistName}",
                        arguments = listOf(
                            navArgument("playlistId") { type = NavType.StringType },
                            navArgument("playlistName") { type = NavType.StringType },
                        ),
                    ) { backStackEntry ->
                        val playlistId = PlaylistId(backStackEntry.arguments?.getString("playlistId").orEmpty())
                        val playlistName = backStackEntry.arguments?.getString("playlistName").orEmpty()
                        val viewModel: PlaylistViewModel = viewModel(
                            factory = PlaylistViewModel.Factory(playlistId, playlistName, container.libraryRepository)
                        )
                        PlaylistScreen(
                            viewModel = viewModel,
                            onVideoClick = { video ->
                                videoLookup[video.id.value] = video
                                navController.navigate("player/${video.id.value}")
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(
                        "player/{videoId}",
                        arguments = listOf(navArgument("videoId") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val videoId = backStackEntry.arguments?.getString("videoId").orEmpty()
                        val cachedVideo = videoLookup[videoId]
                        var resolvedVideo by androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(cachedVideo)
                        }

                        // cachedVideo covers the normal in-app navigation
                        // path (tapped from Home/Channel/Playlist, so the
                        // Video object is already in memory). The
                        // fallback DB lookup only matters for process
                        // death / deep-link resume — see
                        // docs/ARCHITECTURE.md open items.
                        androidx.compose.runtime.LaunchedEffect(videoId) {
                            if (resolvedVideo == null) {
                                resolvedVideo = container.libraryRepository.getVideo(
                                    dev.hudater.localtube.core.model.VideoId(videoId)
                                )
                            }
                        }

                        resolvedVideo?.let { video ->
                            val viewModel: PlayerViewModel = viewModel(
                                factory = PlayerViewModel.Factory(video, container.playerFactory, container.sourceRepository)
                            )
                            PlayerScreen(viewModel = viewModel, video = video)
                        }
                    }

                    composable("settings") {
                        val viewModel: SettingsViewModel = viewModel(
                            factory = SettingsViewModel.Factory(container.sourceRepository, container.smbCredentialStore)
                        )
                        SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}

