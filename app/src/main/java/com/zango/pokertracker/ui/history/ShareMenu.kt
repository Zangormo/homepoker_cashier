package com.zango.pokertracker.ui.history

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zango.pokertracker.R

/**
 * The history's way in and out: import games another host exported, or export some of this
 * phone's own. Each option says in a quiet second line what it moves and which way, because
 * "Import" and "Export" alone do not say what. It mirrors the new-game button from the opposite
 * corner, turning into a close button while the two options rise out of it, and stays in the
 * quieter colour because the green belongs to starting a game.
 */
@Composable
fun ShareMenu(
    expanded: Boolean,
    onToggle: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(OPEN_MILLIS),
        label = "share",
    )
    val origin = TransformOrigin(0f, 1f)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Top to bottom as drawn; the bottom one, nearest the button, arrives first.
        MenuOption(
            visible = expanded,
            delayMillis = STAGGER_MILLIS,
            label = stringResource(R.string.share_import),
            description = stringResource(R.string.share_import_hint),
            icon = Icons.Filled.Download,
            primary = false,
            onClick = onImport,
            origin = origin,
        )
        MenuOption(
            visible = expanded,
            delayMillis = 0,
            label = stringResource(R.string.share_export),
            description = stringResource(R.string.share_export_hint),
            icon = Icons.Filled.Upload,
            primary = false,
            onClick = onExport,
            origin = origin,
        )
        FloatingActionButton(
            onClick = onToggle,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Crossfade(targetState = expanded, animationSpec = tween(OPEN_MILLIS), label = "share-icon") { open ->
                Icon(
                    if (open) Icons.Filled.Close else Icons.Filled.IosShare,
                    contentDescription = stringResource(if (open) R.string.action_cancel else R.string.share_button),
                    modifier = Modifier.rotate(rotation),
                )
            }
        }
    }
}
