package com.example.llamadroid.tama.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog

/**
 * Shared Tama popup: dark title bar, cream body and dark footer over a darkened background art.
 * The body is composed under [tamaLcdLightScheme] and the footer under [tamaLcdDarkScheme], so
 * Material controls inside pick matching on-colours in every app theme (no pinned text colour).
 */
@Composable
fun TamaPopupDialog(
    title: String,
    backgroundAsset: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showFooter: Boolean = true,
    bodyContent: @Composable ColumnScope.() -> Unit,
    footerContent: @Composable RowScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints {
            // Notices raised while this dialog is open draw on top of it, not under its scrim.
            val dialogMaxHeight = if (compact) maxHeight * 0.72f else maxHeight * 0.9f
            val compactBodyMaxHeight = maxHeight * 0.46f
            Card(
                modifier = modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(0.96f)
                    .heightIn(max = dialogMaxHeight),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, TamaDark.copy(alpha = 0.55f))
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    AsyncImage(
                        model = "file:///android_asset/$backgroundAsset",
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.None
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color(0xFF111111).copy(alpha = 0.76f))
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = TamaDark.copy(alpha = 0.78f),
                            border = BorderStroke(1.dp, TamaLight.copy(alpha = 0.14f))
                        ) {
                            Text(
                                title,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = TamaLight,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (compact) {
                                        Modifier.heightIn(max = compactBodyMaxHeight)
                                    } else {
                                        Modifier.weight(1f, fill = false)
                                    }
                                ),
                            shape = RoundedCornerShape(18.dp),
                            color = TamaLight.copy(alpha = 0.985f),
                            contentColor = TamaDark,
                            border = BorderStroke(1.dp, TamaDark.copy(alpha = 0.18f))
                        ) {
                            TamaLcdSurface(dark = false) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    bodyContent()
                                }
                            }
                        }
                        if (showFooter) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = TamaDark.copy(alpha = 0.55f),
                                contentColor = TamaLight,
                                border = BorderStroke(1.dp, TamaLight.copy(alpha = 0.12f))
                            ) {
                                TamaLcdSurface(dark = true) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .padding(horizontal = 6.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        footerContent()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Last child: drawn above the card so notices raised while the dialog is open stay visible.
            TamaDialogNoticeHost()
        }
    }
}

@Composable
internal fun TamaToggleSetting(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Text(description, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TamaHelperText)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = tamaSwitchColors())
    }
}
