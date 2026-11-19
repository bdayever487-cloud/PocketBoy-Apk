package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.RomEntity
import com.example.data.model.RomSystem
import com.example.ui.theme.AtomicIndigo
import com.example.ui.theme.AtomicIndigoContainer
import com.example.ui.theme.ConsoleOutline
import com.example.ui.theme.ConsoleSurface
import com.example.ui.theme.ConsoleSurfaceHigh
import com.example.ui.theme.ConsoleSurfaceVariant
import com.example.ui.theme.CoralButton
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.ElectricLimeContainer
import com.example.ui.theme.ElectricLimeDark
import com.example.ui.theme.GbcCyan
import com.example.ui.theme.GbcCyanContainer
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.DecimalFormat

@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onSelectFolder: () -> Unit,
    onRefreshFolder: () -> Unit,
    onImportSingleRom: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFilterChange: (SystemFilter) -> Unit,
    onPlayRom: (RomEntity) -> Unit,
    onToggleFavorite: (RomEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columns = when {
            maxWidth >= 900.dp -> 3
            maxWidth >= 600.dp -> 2
            else -> 1
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Hero Banner
            item(span = { GridItemSpan(columns) }) {
                HeroHeaderCard(uiState = uiState)
            }

            // Folder Deck Card (Select Folder + Atualizar!)
            item(span = { GridItemSpan(columns) }) {
                FolderControlCard(
                    folderLabel = uiState.settings.romFolderLabel,
                    isScanning = uiState.isScanning,
                    onSelectFolder = onSelectFolder,
                    onRefreshFolder = onRefreshFolder,
                    onImportSingleRom = onImportSingleRom
                )
            }

            // Search & System Filter Chips when we have ROMs
            if (uiState.totalRomCount > 0) {
                item(span = { GridItemSpan(columns) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = { Text(stringResource(R.string.search_roms_hint)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary)
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricLime,
                                unfocusedBorderColor = ConsoleOutline,
                                focusedContainerColor = ConsoleSurface,
                                unfocusedContainerColor = ConsoleSurface
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("search_roms_input")
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(SystemFilter.entries) { filter ->
                                val label = when (filter) {
                                    SystemFilter.ALL -> "${stringResource(R.string.filter_all)} (${uiState.totalRomCount})"
                                    SystemFilter.GB -> "GB (${uiState.gbCount})"
                                    SystemFilter.GBC -> "GBC (${uiState.gbcCount})"
                                    SystemFilter.GBA -> "GBA (${uiState.gbaCount})"
                                }
                                val selected = uiState.selectedFilter == filter
                                FilterChip(
                                    selected = selected,
                                    onClick = { onFilterChange(filter) },
                                    label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = ElectricLime,
                                        selectedLabelColor = ElectricLimeDark,
                                        containerColor = ConsoleSurfaceVariant,
                                        labelColor = TextSecondary
                                    ),
                                    modifier = Modifier.testTag("filter_${filter.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
            }

            // Empty / Onboarding State OR ROM Cartridge Grid
            if (uiState.roms.isEmpty()) {
                item(span = { GridItemSpan(columns) }) {
                    EmptyLibraryCard(
                        hasFolderLinked = uiState.settings.romFolderUri != null,
                        hasRomsAtAll = uiState.totalRomCount > 0,
                        onSelectFolder = onSelectFolder,
                        onRefreshFolder = onRefreshFolder
                    )
                }
            } else {
                items(
                    items = uiState.roms,
                    key = { it.uriString }
                ) { rom ->
                    RomCartridgeCard(
                        rom = rom,
                        onPlay = { onPlayRom(rom) },
                        onToggleFavorite = { onToggleFavorite(rom) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroHeaderCard(uiState: LibraryUiState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(156.dp)
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, ConsoleOutline, RoundedCornerShape(22.dp))
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_console_hero),
            contentDescription = stringResource(R.string.hero_title),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            ObsidianBg.copy(alpha = 0.94f),
                            ObsidianBg.copy(alpha = 0.72f),
                            ObsidianBg.copy(alpha = 0.25f)
                        )
                    )
                )
                .padding(18.dp)
        ) {
            Column(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = ElectricLimeContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "GB • GBC • GBA",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricLime,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    color = TextPrimary
                )
                Text(
                    text = if (uiState.totalRomCount == 0) {
                        stringResource(R.string.hero_subtitle)
                    } else {
                        "${uiState.totalRomCount} jogos prontos para jogar"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun FolderControlCard(
    folderLabel: String?,
    isScanning: Boolean,
    onSelectFolder: () -> Unit,
    onRefreshFolder: () -> Unit,
    onImportSingleRom: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "refresh_spin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = ConsoleSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ConsoleOutline, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (folderLabel != null) ElectricLimeContainer else AtomicIndigoContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = if (folderLabel != null) ElectricLime else AtomicIndigo
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (folderLabel != null) "Pasta de ROMs conectada" else stringResource(R.string.folder_not_linked),
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = folderLabel ?: "Escolha a pasta criada no gerenciador de arquivos",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (folderLabel != null) ElectricLime else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = if (folderLabel != null) onRefreshFolder else onSelectFolder,
                    enabled = !isScanning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricLime,
                        contentColor = ElectricLimeDark
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag(if (folderLabel != null) "refresh_roms_button" else "select_folder_button")
                ) {
                    Icon(
                        imageVector = if (folderLabel != null) Icons.Filled.Refresh else Icons.Filled.CreateNewFolder,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .then(if (isScanning) Modifier.rotate(spinAngle) else Modifier)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            isScanning -> stringResource(R.string.scan_in_progress)
                            folderLabel != null -> stringResource(R.string.action_refresh_roms)
                            else -> stringResource(R.string.action_select_folder)
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                if (folderLabel != null) {
                    OutlinedButton(
                        onClick = onSelectFolder,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("change_folder_button")
                    ) {
                        Text(
                            text = stringResource(R.string.action_change_folder),
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onImportSingleRom,
                    modifier = Modifier.testTag("import_single_rom_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.UploadFile,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_import_single_rom),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryCard(
    hasFolderLinked: Boolean,
    hasRomsAtAll: Boolean,
    onSelectFolder: () -> Unit,
    onRefreshFolder: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ConsoleSurface),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ConsoleOutline, RoundedCornerShape(22.dp))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_empty_folder),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(16.dp))
            )

            if (hasRomsAtAll) {
                Text(
                    text = "Nenhum jogo corresponde ao filtro",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            } else if (hasFolderLinked) {
                Text(
                    text = stringResource(R.string.empty_folder_no_roms),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = stringResource(R.string.empty_folder_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Button(
                    onClick = onRefreshFolder,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricLime,
                        contentColor = ElectricLimeDark
                    ),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_refresh_roms))
                }
            } else {
                Text(
                    text = stringResource(R.string.empty_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ConsoleSurfaceVariant)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.empty_step_1), style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text(stringResource(R.string.empty_step_2), style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text(stringResource(R.string.empty_step_3), style = MaterialTheme.typography.bodyMedium, color = ElectricLime)
                }
                Button(
                    onClick = onSelectFolder,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricLime,
                        contentColor = ElectricLimeDark
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_select_folder))
                }
            }
        }
    }
}

@Composable
private fun RomCartridgeCard(
    rom: RomEntity,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val (badgeColor, badgeBg) = when (rom.system) {
        RomSystem.GB -> ElectricLime to ElectricLimeContainer
        RomSystem.GBC -> GbcCyan to GbcCyanContainer
        RomSystem.GBA -> AtomicIndigo to AtomicIndigoContainer
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = ConsoleSurface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ConsoleOutline, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onPlay)
            .testTag("rom_card_${rom.fileName}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Miniature physical cartridge icon badge
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(badgeBg)
                    .border(1.5.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.SportsEsports,
                        contentDescription = rom.system.fullName,
                        tint = badgeColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = rom.system.shortLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = badgeColor
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = rom.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = ConsoleSurfaceHigh,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = rom.mapperInfo,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = formatFileSize(rom.fileSizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
                if (rom.playTimeSeconds > 0L) {
                    Text(
                        text = "Jogado: ${formatPlayDuration(rom.playTimeSeconds)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricLime
                    )
                }
            }

            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (rom.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Favorito",
                    tint = if (rom.isFavorite) CoralButton else TextMuted
                )
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.play_now),
                    tint = ObsidianBg
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "ROM"
    val df = DecimalFormat("#.#")
    return when {
        bytes >= 1024 * 1024 -> "${df.format(bytes / (1024.0 * 1024.0))} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}

private fun formatPlayDuration(seconds: Long): String {
    val mins = seconds / 60
    val hrs = mins / 60
    return if (hrs > 0) "${hrs}h ${mins % 60}m" else "${maxOf(1L, mins)}m"
}
