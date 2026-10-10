package com.tim.gallery.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity

/**
 * Six dots and a number pad. Calls [onComplete] once six digits are in, then clears itself.
 * [onSystemUnlock] adds a fingerprint/face/screen-lock button in the bottom-left corner.
 */
@Composable
fun PinPad(
    title: String,
    subtitle: String?,
    error: String?,
    busy: Boolean,
    onComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSystemUnlock: (() -> Unit)? = null,
) {
    var pin by remember { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme

    fun press(d: Char) {
        if (busy || pin.length >= Vault.PIN_LENGTH) return
        pin += d
        if (pin.length == Vault.PIN_LENGTH) {
            val entered = pin
            pin = ""
            onComplete(entered)
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(Vault.PIN_LENGTH) { i ->
                val filled = i < pin.length
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (filled) scheme.primary else scheme.surface)
                        .border(2.dp, if (filled) scheme.primary else scheme.outline, CircleShape)
                )
            }
        }
        Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                error != null -> Text(
                    error,
                    color = scheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
        val rows = listOf("123", "456", "789")
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                r.forEach { d -> Key(d.toString()) { press(d) } }
            }
            Spacer(Modifier.height(14.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                if (onSystemUnlock != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(76.dp).clip(CircleShape).clickable(onClick = onSystemUnlock),
                    ) {
                        Icon(Icons.Outlined.Fingerprint, contentDescription = "Unlock with fingerprint, face or screen lock", modifier = Modifier.size(34.dp))
                    }
                }
            }
            Key("0") { press('0') }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(76.dp).clip(CircleShape).clickable(enabled = pin.isNotEmpty()) { pin = pin.dropLast(1) },
            ) {
                Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = "Delete digit")
            }
        }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        // Sized in dp so very large system text can't overflow the key
        Text(label, fontSize = with(LocalDensity.current) { 28.dp.toSp() }, style = MaterialTheme.typography.headlineMedium)
    }
}
