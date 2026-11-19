package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.local.SaveSlotEntity
import com.example.data.model.RomSystem
import com.example.emulator.EmulatorButtons
import com.example.emulator.EmulatorSessionViewModel
import com.example.emulator.PlayerUiState
import com.example.ui.components.ActionCluster
import com.example.ui.components.MultiTouchDPad
import com.example.ui.components.PillControlButton
import com.example.ui.theme.AmberSave
import com.example.ui.theme.AtomicIndigo
import com.example.ui.theme.ConsoleOutline
import com.example.ui.theme.ConsoleSurface
import com.example.ui.theme.ConsoleSurfaceHigh
import com.example.ui.theme.ConsoleSurfaceVariant
import com.example.ui.theme.CoralButton
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.ElectricLimeDark
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    romUriString: String,
    initialSlotIndex: Int,
    onExit: () -> Unit,
    sessionViewModel: EmulatorSessionViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by sessionViewModel.uiState.collectAsStateWithLifecycle()
    val settings by sessionViewModel.settings.collectAsStateWithLifecycle()
    val saveSlots by sessionViewModel.saveSlots.collectAsStateWithLifecycle()
    val frameTick by sessionViewModel.frameTick.collectAsStateWithLifecycle()

    var showPauseSheet by remember { mutableStateOf(false) }

    // Input mask state composed from touch D-pad, touch buttons, and physical gamepad/keys
    var dpadMask by remember { mutableIntStateOf(0) }
    var actionMask by remember { mutableIntStateOf(0) }
    var hwKeyMask by remember { mutableIntStateOf(0) }

    fun pushMask() {
        sessionViewModel.setInputMask(dpadMask or actionMask or hwKeyMask)
    }

    LaunchedEffect(romUriString, initialSlotIndex) {
        sessionViewModel.startSession(romUriString, initialSlotIndex)
    }

    LaunchedEffect(Unit) {
        sessionViewModel.events.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            sessionViewModel.stopSession(saveAuto = true)
        }
    }

    BackHandler {
        if (showPauseSheet) {
            showPauseSheet = false
            sessionViewModel.setPaused(false)
        } else {
            showPauseSheet = true
            sessionViewModel.setPaused(true)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .onPreviewKeyEvent { keyEvent ->
                val bit = mapAndroidKeyToEmulatorBit(keyEvent.nativeKeyEvent.keyCode)
                if (bit == 0) return@onPreviewKeyEvent false
                hwKeyMask = when (keyEvent.type) {
                    KeyEventType.KeyDown -> hwKeyMask or bit
                    KeyEventType.KeyUp -> hwKeyMask and bit.inv()
                    else -> hwKeyMask
                }
                pushMask()
                true
            }
    ) {
        when (val state = uiState) {
            is PlayerUiState.Loading -> {
                CircularProgressIndicator(
                    color = ElectricLime,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            is PlayerUiState.Error -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(state.message, style = MaterialTheme.typography.titleMedium, color = CoralButton)
                    Button(onClick = onExit) {
                        Text(stringResource(R.string.action_exit_game))
                    }
                }
            }
            is PlayerUiState.Ready -> {
                val frameBitmap = remember(state.width, state.height) {
                    Bitmap.createBitmap(state.width, state.height, Bitmap.Config.ARGB_8888)
                }
                val imageBitmap = remember(frameBitmap) { frameBitmap.asImageBitmap() }
                val isGba = state.rom.system == RomSystem.GBA

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isLandscape = maxWidth > maxHeight

                    if (isLandscape) {
                        // Wide Landscape Console Layout
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left hand: L shoulder + D-Pad + Select
                            Column(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (isGba) {
                                    PillControlButton(
                                        label = "L",
                                        opacity = settings.controllerOpacity,
                                        hapticEnabled = settings.hapticEnabled,
                                        isShoulder = true,
                                        testTag = "btn_l",
                                        onPressedChanged = { down ->
                                            actionMask = if (down) actionMask or EmulatorButtons.L else actionMask and EmulatorButtons.L.inv()
                                            pushMask()
                                        }
                                    )
                                } else {
                                    Spacer(Modifier.height(24.dp))
                                }

                                MultiTouchDPad(
                                    size = 148.dp,
                                    opacity = settings.controllerOpacity,
                                    hapticEnabled = settings.hapticEnabled,
                                    onDirectionMaskChanged = { mask ->
                                        dpadMask = mask
                                        pushMask()
                                    }
                                )

                                PillControlButton(
                                    label = "SELECT",
                                    opacity = settings.controllerOpacity,
                                    hapticEnabled = settings.hapticEnabled,
                                    testTag = "btn_select",
                                    onPressedChanged = { down ->
                                        actionMask = if (down) actionMask or EmulatorButtons.SELECT else actionMask and EmulatorButtons.SELECT.inv()
                                        pushMask()
                                    }
                                )
                            }

                            // Center Screen + Top HUD
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(horizontal = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                EmulatorTopHud(
                                    state = state,
                                    showFps = settings.showFps,
                                    onOpenMenu = {
                                        showPauseSheet = true
                                        sessionViewModel.setPaused(true)
                                    },
                                    onQuickSave = { sessionViewModel.saveToSlot(1) },
                                    onQuickLoad = { sessionViewModel.loadFromSlot(1) },
                                    onToggleFastForward = { sessionViewModel.toggleFastForward() },
                                    onToggleMute = { sessionViewModel.toggleMute() }
                                )
                                EmulatorScreenViewport(
                                    frameTick = frameTick,
                                    frameBitmap = frameBitmap,
                                    imageBitmap = imageBitmap,
                                    coreWidth = state.width,
                                    coreHeight = state.height,
                                    crispFilter = settings.pixelFilter,
                                    copyFrame = sessionViewModel::copyLatestFrame,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                )
                            }

                            // Right hand: R shoulder + A/B + Start
                            Column(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (isGba) {
                                    PillControlButton(
                                        label = "R",
                                        opacity = settings.controllerOpacity,
                                        hapticEnabled = settings.hapticEnabled,
                                        isShoulder = true,
                                        testTag = "btn_r",
                                        onPressedChanged = { down ->
                                            actionMask = if (down) actionMask or EmulatorButtons.R else actionMask and EmulatorButtons.R.inv()
                                            pushMask()
                                        }
                                    )
                                } else {
                                    Spacer(Modifier.height(24.dp))
                                }

                                ActionCluster(
                                    opacity = settings.controllerOpacity,
                                    hapticEnabled = settings.hapticEnabled,
                                    onAChanged = { down ->
                                        actionMask = if (down) actionMask or EmulatorButtons.A else actionMask and EmulatorButtons.A.inv()
                                        pushMask()
                                    },
                                    onBChanged = { down ->
                                        actionMask = if (down) actionMask or EmulatorButtons.B else actionMask and EmulatorButtons.B.inv()
                                        pushMask()
                                    }
                                )

                                PillControlButton(
                                    label = "START",
                                    opacity = settings.controllerOpacity,
                                    hapticEnabled = settings.hapticEnabled,
                                    testTag = "btn_start",
                                    onPressedChanged = { down ->
                                        actionMask = if (down) actionMask or EmulatorButtons.START else actionMask and EmulatorButtons.START.inv()
                                        pushMask()
                                    }
                                )
                            }
                        }
                    } else {
                        // Portrait Handheld Deck Layout
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            EmulatorTopHud(
                                state = state,
                                showFps = settings.showFps,
                                onOpenMenu = {
                                    showPauseSheet = true
                                    sessionViewModel.setPaused(true)
                                },
                                onQuickSave = { sessionViewModel.saveToSlot(1) },
                                onQuickLoad = { sessionViewModel.loadFromSlot(1) },
                                onToggleFastForward = { sessionViewModel.toggleFastForward() },
                                onToggleMute = { sessionViewModel.toggleMute() }
                            )

                            EmulatorScreenViewport(
                                frameTick = frameTick,
                                frameBitmap = frameBitmap,
                                imageBitmap = imageBitmap,
                                coreWidth = state.width,
                                coreHeight = state.height,
                                crispFilter = settings.pixelFilter,
                                copyFrame = sessionViewModel::copyLatestFrame,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )

                            // Handheld Controller Deck
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                if (isGba) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        PillControlButton(
                                            label = "L",
                                            opacity = settings.controllerOpacity,
                                            hapticEnabled = settings.hapticEnabled,
                                            isShoulder = true,
                                            testTag = "btn_l",
                                            onPressedChanged = { down ->
                                                actionMask = if (down) actionMask or EmulatorButtons.L else actionMask and EmulatorButtons.L.inv()
                                                pushMask()
                                            }
                                        )
                                        PillControlButton(
                                            label = "R",
                                            opacity = settings.controllerOpacity,
                                            hapticEnabled = settings.hapticEnabled,
                                            isShoulder = true,
                                            testTag = "btn_r",
                                            onPressedChanged = { down ->
                                                actionMask = if (down) actionMask or EmulatorButtons.R else actionMask and EmulatorButtons.R.inv()
                                                pushMask()
                                            }
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MultiTouchDPad(
                                        size = 154.dp,
                                        opacity = settings.controllerOpacity,
                                        hapticEnabled = settings.hapticEnabled,
                                        onDirectionMaskChanged = { mask ->
                                            dpadMask = mask
                                            pushMask()
                                        }
                                    )

                                    ActionCluster(
                                        opacity = settings.controllerOpacity,
                                        hapticEnabled = settings.hapticEnabled,
                                        onAChanged = { down ->
                                            actionMask = if (down) actionMask or EmulatorButtons.A else actionMask and EmulatorButtons.A.inv()
                                            pushMask()
                                        },
                                        onBChanged = { down ->
                                            actionMask = if (down) actionMask or EmulatorButtons.B else actionMask and EmulatorButtons.B.inv()
                                            pushMask()
                                        }
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PillControlButton(
                                        label = "SELECT",
                                        opacity = settings.controllerOpacity,
                                        hapticEnabled = settings.hapticEnabled,
                                        testTag = "btn_select",
                                        onPressedChanged = { down ->
                                            actionMask = if (down) actionMask or EmulatorButtons.SELECT else actionMask and EmulatorButtons.SELECT.inv()
                                            pushMask()
                                        }
                                    )
                                    Spacer(Modifier.width(24.dp))
                                    PillControlButton(
                                        label = "START",
                                        opacity = settings.controllerOpacity,
                                        hapticEnabled = settings.hapticEnabled,
                                        testTag = "btn_start",
                                        onPressedChanged = { down ->
                                            actionMask = if (down) actionMask or EmulatorButtons.START else actionMask and EmulatorButtons.START.inv()
                                            pushMask()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (showPauseSheet) {
                    ModalBottomSheet(
                        onDismissRequest = {
                            showPauseSheet = false
                            sessionViewModel.setPaused(false)
                        },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = ConsoleSurface
                    ) {
                        PauseAndSavesSheet(
                            romTitle = state.rom.displayTitle,
                            system = state.rom.system,
                            saveSlots = saveSlots,
                            onResume = {
                                showPauseSheet = false
                                sessionViewModel.setPaused(false)
                            },
                            onSaveSlot = { slotIdx ->
                                sessionViewModel.saveToSlot(slotIdx)
                            },
                            onLoadSlot = { slotIdx ->
                                showPauseSheet = false
                                sessionViewModel.loadFromSlot(slotIdx)
                            },
                            onReset = {
                                showPauseSheet = false
                                sessionViewModel.resetConsole()
                            },
                            onExit = {
                                showPauseSheet = false
                                sessionViewModel.stopSession(saveAuto = true)
                                onExit()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmulatorTopHud(
    state: PlayerUiState.Ready,
    showFps: Boolean,
    onOpenMenu: () -> Unit,
    onQuickSave: () -> Unit,
    onQuickLoad: () -> Unit,
    onToggleFastForward: () -> Unit,
    onToggleMute: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            FilledTonalIconButton(
                onClick = onOpenMenu,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = ConsoleSurfaceVariant,
                    contentColor = TextPrimary
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("btn_pause_menu")
            ) {
                Icon(Icons.Filled.Menu, contentDescription = "Menu e Saves")
            }
            Column {
                Text(
                    text = state.rom.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (showFps) {
                    Text(
                        text = "${state.fps} FPS • ${state.rom.system.shortLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricLime
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalIconButton(
                onClick = onQuickSave,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = ConsoleSurfaceVariant,
                    contentColor = AmberSave
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("btn_quick_save")
            ) {
                Icon(Icons.Filled.BookmarkAdd, contentDescription = stringResource(R.string.action_quick_save))
            }
            FilledTonalIconButton(
                onClick = onQuickLoad,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = ConsoleSurfaceVariant,
                    contentColor = ElectricLime
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("btn_quick_load")
            ) {
                Icon(Icons.Filled.FolderSpecial, contentDescription = stringResource(R.string.action_quick_load))
            }
            FilledTonalIconButton(
                onClick = onToggleFastForward,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (state.isFastForward) ElectricLime else ConsoleSurfaceVariant,
                    contentColor = if (state.isFastForward) ElectricLimeDark else TextPrimary
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("btn_fast_forward")
            ) {
                Icon(Icons.Filled.FastForward, contentDescription = "Avanço Rápido")
            }
            FilledTonalIconButton(
                onClick = onToggleMute,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = ConsoleSurfaceVariant,
                    contentColor = if (state.isMuted) CoralButton else TextPrimary
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("btn_mute")
            ) {
                Icon(
                    imageVector = if (state.isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = "Áudio"
                )
            }
        }
    }
}

@Composable
private fun EmulatorScreenViewport(
    frameTick: Long,
    frameBitmap: Bitmap,
    imageBitmap: androidx.compose.ui.graphics.ImageBitmap,
    coreWidth: Int,
    coreHeight: Int,
    crispFilter: Boolean,
    copyFrame: (Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF07090D))
            .border(2.dp, ConsoleOutline, RoundedCornerShape(18.dp))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("emulator_screen_canvas")
        ) {
            // Read frameTick inside Canvas draw scope so Compose invalidates only Canvas!
            @Suppress("UNUSED_VARIABLE")
            val tick = frameTick
            copyFrame(frameBitmap)

            val scaleX = size.width / coreWidth.toFloat()
            val scaleY = size.height / coreHeight.toFloat()
            val scale = minOf(scaleX, scaleY)
            val dstW = (coreWidth * scale).toInt().coerceAtLeast(1)
            val dstH = (coreHeight * scale).toInt().coerceAtLeast(1)
            val offX = ((size.width - dstW) / 2f).toInt()
            val offY = ((size.height - dstH) / 2f).toInt()

            drawImage(
                image = imageBitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(coreWidth, coreHeight),
                dstOffset = IntOffset(offX, offY),
                dstSize = IntSize(dstW, dstH),
                filterQuality = if (crispFilter) FilterQuality.None else FilterQuality.Low
            )
        }
    }
}

@Composable
private fun PauseAndSavesSheet(
    romTitle: String,
    system: RomSystem,
    saveSlots: List<SaveSlotEntity>,
    onResume: () -> Unit,
    onSaveSlot: (Int) -> Unit,
    onLoadSlot: (Int) -> Unit,
    onReset: () -> Unit,
    onExit: () -> Unit
) {
    val slotMap = remember(saveSlots) { saveSlots.associateBy { it.slotIndex } }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(romTitle, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text(system.fullName, style = MaterialTheme.typography.labelMedium, color = ElectricLime)
                }
                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricLime,
                        contentColor = ElectricLimeDark
                    ),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_resume))
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.save_slots_title),
                style = MaterialTheme.typography.labelMedium,
                color = AmberSave
            )
        }

        // Slots 0 (Auto) and 1..4 (Manual)
        items(5) { slotIndex ->
            val slot = slotMap[slotIndex]
            val thumb = remember(slot?.screenshotPath, slot?.updatedAt) {
                val p = slot?.screenshotPath ?: return@remember null
                val f = File(p)
                if (!f.exists()) return@remember null
                runCatching { BitmapFactory.decodeFile(p)?.asImageBitmap() }.getOrNull()
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ConsoleSurfaceVariant)
                    .border(1.dp, ConsoleOutline, RoundedCornerShape(14.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 72.dp, height = 54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ConsoleSurfaceHigh),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = if (slotIndex == 0) "AUTO" else "#$slotIndex",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (slotIndex == 0) stringResource(R.string.slot_auto) else stringResource(R.string.slot_numbered, slotIndex),
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = if (slot != null) {
                            SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(slot.updatedAt))
                        } else {
                            stringResource(R.string.slot_empty)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                if (slotIndex > 0) {
                    Button(
                        onClick = { onSaveSlot(slotIndex) },
                        colors = ButtonDefaults.buttonColors(containerColor = AtomicIndigo),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text(stringResource(R.string.action_save), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (slot != null) {
                    OutlinedButton(
                        onClick = { onLoadSlot(slotIndex) },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text(stringResource(R.string.action_load), style = MaterialTheme.typography.labelMedium, color = ElectricLime)
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, tint = TextPrimary)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_reset_console), color = TextPrimary)
                }
                Button(
                    onClick = onExit,
                    colors = ButtonDefaults.buttonColors(containerColor = CoralButton),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_exit_game))
                }
            }
        }
    }
}

private fun mapAndroidKeyToEmulatorBit(keyCode: Int): Int = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> EmulatorButtons.UP
    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> EmulatorButtons.DOWN
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> EmulatorButtons.LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> EmulatorButtons.RIGHT
    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_J -> EmulatorButtons.A
    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_K -> EmulatorButtons.B
    KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_Q -> EmulatorButtons.L
    KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_E -> EmulatorButtons.R
    KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> EmulatorButtons.START
    KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_SPACE -> EmulatorButtons.SELECT
    else -> 0
}
