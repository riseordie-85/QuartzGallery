package com.tim.gallery.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class EmojiGroup(val icon: String, val name: String, list: String) {
    val emojis: List<String> = list.trim().split(Regex("\\s+"))
}

private val EMOJI = listOf(
    EmojiGroup(
        "😀", "Smileys",
        """😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 ☠️ 💩 🤡 👹 👺 👻 👽 👾 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾""",
    ),
    EmojiGroup(
        "👍", "Hands & people",
        """👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💪 🦾 👀 👁️ 👅 👄 🧠 🫶 🙋 🙆 🙅 🤷 🤦 💁 🙇 🕺 💃 👯 🧘 🏃 🚶 🧑‍💻 🧑‍🎨 🧑‍🍳 🧑‍🚀 👶 🧒 🧑 🧓 👮 🕵️ 💂 🥷 👷 🤴 👸 🦸 🦹 🧙 🧚 🧛 🧜 🧞 🧟""",
    ),
    EmojiGroup(
        "❤️", "Hearts & symbols",
        """❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💯 💢 💥 💫 💦 💨 🔥 ✨ ⭐ 🌟 ⚡ ☀️ 🌈 ☁️ ❄️ 💧 🎵 🎶 ✅ ❌ ❗ ❓ ⚠️ 🚫 💤 💬 💭 🗯️ ♻️ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🔺 🔻 🔶 🔷 ➡️ ⬅️ ⬆️ ⬇️ ↗️ 🔄 🆒 🆕 🆗 🆘 🔝""",
    ),
    EmojiGroup(
        "🐶", "Animals & nature",
        """🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🦖 🦕 🐙 🦑 🦀 🐠 🐬 🐳 🦈 🐊 🐘 🦒 🦘 🐕 🐈 🐾 🌵 🌲 🌴 🌱 🍀 🍁 🍂 🌷 🌹 🌺 🌸 🌼 🌻 🍄 🌙 🌎 🌊""",
    ),
    EmojiGroup(
        "🍕", "Food & drink",
        """🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🌶️ 🌽 🥕 🥐 🍞 🧀 🥚 🍳 🥓 🥞 🧇 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍣 🍱 🍤 🍙 🍦 🍩 🍪 🎂 🍰 🧁 🍫 🍬 🍭 🍿 ☕ 🍵 🧋 🥤 🍺 🍻 🥂 🍷 🍸 🍹""",
    ),
    EmojiGroup(
        "⚽", "Activities & things",
        """⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 ⛳ 🎣 🎿 🏂 🏆 🥇 🥈 🥉 🎮 🕹️ 🎲 🧩 🎯 🎳 🎨 🎬 🎤 🎧 🎸 🎹 🥁 🎉 🎊 🎈 🎁 🎀 📷 📸 📱 💻 ⌚ 💡 🔦 📚 ✏️ 📌 📍 ✂️ 🔒 🔑 🔨 🧰 💰 💎 🕶️ 👑 💍 🧢 👟 👗 👕 🎒""",
    ),
    EmojiGroup(
        "✈️", "Travel & places",
        """🚗 🚕 🚙 🚌 🏎️ 🚓 🚑 🚒 🚚 🚜 🏍️ 🚲 🛴 ✈️ 🚀 🛸 🚁 ⛵ 🚤 🚢 ⚓ 🗺️ 🏔️ ⛰️ 🌋 🏕️ 🏖️ 🏝️ 🏠 🏡 🏙️ 🌆 🌃 🌉 🎡 🎢 🗽 🗼 🏰 ⛺ 🌅 🌄 🎆 🎇 🏁 🚩 🏳️‍🌈 🇺🇸 🇨🇦 🇲🇽 🇬🇧 🇮🇪 🇫🇷 🇩🇪 🇮🇹 🇪🇸 🇯🇵 🇰🇷 🇧🇷 🇦🇺""",
    ),
)

/** Emoji picker. The picked emoji is added as a sticker drawn with the phone's emoji font. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var group by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(EMOJI) { i, g ->
                FilterChip(
                    selected = i == group,
                    onClick = { group = i },
                    label = { Text(if (i == group) "${g.icon}  ${g.name}" else g.icon, fontSize = 16.sp) },
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(52.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 32.dp),
            modifier = Modifier.height(380.dp),
        ) {
            items(EMOJI[group].emojis) { e ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPick(e) },
                ) {
                    Text(e, fontSize = 30.sp)
                }
            }
        }
    }
}

/** Shape picker: rectangles, circles, lines, arrows, stars, hearts, speech bubbles... */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShapeSheet(onDismiss: () -> Unit, onPick: (ShapeKind) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Shapes",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(ShapeKind.entries) { kind ->
                val sample = remember(kind) { ShapeLayer.default(1L, kind).let { it.copy(scale = if (kind.isStroke) 0.8f else 0.62f) } }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onPick(kind) }
                        .padding(8.dp),
                ) {
                    Canvas(Modifier.fillMaxWidth().aspectRatio(1.3f)) {
                        val out = FSize(size.width, size.height)
                        drawIntoCanvas { c ->
                            EditRenderer.drawLayer(c.nativeCanvas, sample.copy(scale = sample.scale * size.width / out.shortSide), out, LayerAssets.NONE)
                        }
                    }
                    Text(kind.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
