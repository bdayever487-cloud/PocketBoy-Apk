package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.SaveSlotEntity
import com.example.data.model.RomSystem
import com.example.ui.theme.AmberSave
import com.example.ui.theme.AtomicIndigo
import com.example.ui.theme.AtomicIndigoContainer
import com.example.ui.theme.ConsoleOutline
import com.example.ui.theme.ConsoleSurface
import com.example.ui.theme.ConsoleSurfaceHigh
import com.example.ui.theme.ConsoleSurfaceVariant
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.ElectricLimeContainer
import com.example.ui.theme.ElectricLimeDark
import com.example.ui.theme.GbcCyan
import com.example.ui.theme.GbcCyanContainer
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SavesScreen(
    saveSlots: List<SaveSlotEntity>,
    onResumeSlot: (SaveSlotEntity) -> Unit,
    onDeleteSlot: (SaveSlotEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    if (saveSlots.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = ConsoleSurface),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ConsoleOutline, RoundedCornerShape(22.dp))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(ConsoleSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bookmark,
                            contentDescription = null,
                            tint = AmberSave,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.saves_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.saves_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = stringResource(R.string.save_slots_title),
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        items(saveSlots, key = { it.id }) { slot ->
            SaveSlotCard(
                slot = slot,
                onResume = { onResumeSlot(slot) },
                onDelete = { onDeleteSlot(slot) }
            )
        }
    }
}

@Composable
private fun SaveSlotCard(
    slot: SaveSlotEntity,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    val thumbBitmap = remember(slot.screenshotPath, slot.updatedAt) {
        val path = slot.screenshotPath ?: return@remember null
        val file = File(path)
        if (!file.exists()) return@remember null
        runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
    }

    val (sysColor, sysBg) = when (slot.system) {
        RomSystem.GB -> ElectricLime to ElectricLimeContainer
        RomSystem.GBC -> GbcCyan to GbcCyanContainer
        RomSystem.GBA -> AtomicIndigo to AtomicIndigoContainer
    }

    val dateLabel = remember(slot.updatedAt) {
        SimpleDateFormat("dd/MM/yy • HH:mm", Locale.getDefault()).format(Date(slot.updatedAt))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = ConsoleSurface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ConsoleOutline, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onResume)
            .testTag("save_slot_card_${slot.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ConsoleSurfaceHigh)
                    .border(1.dp, ConsoleOutline, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbBitmap != null) {
                    Image(
                        bitmap = thumbBitmap,
                        contentDescription = slot.romTitle,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = null,
                        tint = AmberSave
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = slot.romTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(color = sysBg, shape = RoundedCornerShape(6.dp)) {
                        Text(
                            text = slot.system.shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = sysColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Surface(color = ConsoleSurfaceVariant, shape = RoundedCornerShape(6.dp)) {
                        Text(
                            text = if (slot.slotIndex == 0) stringResource(R.string.slot_auto) else "Slot ${slot.slotIndex}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberSave,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricLime,
                        contentColor = ElectricLimeDark
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.continue_game), style = MaterialTheme.typography.labelMedium)
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Excluir save",
                        tint = TextMuted
                    )
                }
            }
        }
    }
}
