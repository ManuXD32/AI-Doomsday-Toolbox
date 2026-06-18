package com.example.llamadroid.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.ui.components.AppScreenScaffold

/**
 * About screen with credits, donations, and library info.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(navController: NavController) {
    val context = LocalContext.current
    
    AppScreenScaffold(
        title = stringResource(R.string.about_title),
        subtitle = stringResource(R.string.settings_about_desc),
        onBack = { navController.popBackStack() }
    ) { _ ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Info Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "🛠️",
                            fontSize = 64.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${stringResource(R.string.about_version)} ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            
            // Developer Info
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.about_developer),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "👨‍💻",
                                fontSize = 32.sp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.about_developer_name),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            IconButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ManuXD32"))
                                    context.startActivity(intent)
                                }
                            ) {
                                Icon(Icons.Default.Share, stringResource(R.string.about_github))
                            }
                        }
                    }
                }
            }
            
            // Donation Links
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.about_support),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Ko-fi Button
                        DonationButton(
                            emoji = "☕",
                            text = stringResource(R.string.about_kofi),
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/L3L61QAJ1S"))
                                context.startActivity(intent)
                            }
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // PayPal Button
                        DonationButton(
                            emoji = "💳",
                            text = stringResource(R.string.about_paypal),
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/ManuelG815"))
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            }
            
            // Open Source Libraries
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.about_libraries),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.about_libraries_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        LibraryItem(
                            name = "llama.cpp",
                            description = stringResource(R.string.about_llama_cpp),
                            url = "https://github.com/ggerganov/llama.cpp"
                        )
                        LibraryItem(
                            name = "whisper.cpp",
                            description = stringResource(R.string.about_whisper_cpp),
                            url = "https://github.com/ggerganov/whisper.cpp"
                        )
                        LibraryItem(
                            name = "stable-diffusion.cpp",
                            description = stringResource(R.string.about_stable_diffusion),
                            url = "https://github.com/leejet/stable-diffusion.cpp"
                        )
                        LibraryItem(
                            name = "Supertonic",
                            description = stringResource(R.string.about_supertonic_desc),
                            url = "https://github.com/supertone-inc/supertonic"
                        )
                        LibraryItem(
                            name = "Quadtrix.cpp",
                            description = stringResource(R.string.about_quadtrix_cpp_desc),
                            url = "https://github.com/Eamon2009/Quadtrix.cpp"
                        )
                        LibraryItem(
                            name = "FFmpeg",
                            description = stringResource(R.string.about_ffmpeg),
                            url = "https://ffmpeg.org"
                        )
                        LibraryItem(
                            name = "Stable Diffusion Android (SDAI)",
                            description = stringResource(R.string.about_sdai_desc),
                            url = "https://github.com/ShiftHackZ/Stable-Diffusion-Android"
                        )
                        LibraryItem(
                            name = "Kiwix-tools",
                            description = stringResource(R.string.about_zim_desc),
                            url = "https://github.com/kiwix/kiwix-tools"
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.about_compatible_tools),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        LibraryItem(
                            name = "EasyDataset",
                            description = stringResource(R.string.about_easy_dataset_desc),
                            url = "https://github.com/ConardLi/easy-dataset"
                        )
                        LibraryItem(
                            name = "Ollama",
                            description = stringResource(R.string.about_ollama_desc),
                            url = "https://github.com/ollama/ollama"
                        )
                        LibraryItem(
                            name = "Open WebUI",
                            description = stringResource(R.string.about_open_webui_desc),
                            url = "https://github.com/open-webui/open-webui"
                        )
                        LibraryItem(
                            name = "Big-AGI",
                            description = stringResource(R.string.about_big_agi_desc),
                            url = "https://github.com/ManuXD32/big-AGI/tree/v2-dev"
                        )
                        LibraryItem(
                            name = "Oobabooga",
                            description = stringResource(R.string.about_oobabooga_desc),
                            url = "https://github.com/ManuXD32/textgen"
                        )
                        LibraryItem(
                            name = "FastSDCPU",
                            description = stringResource(R.string.about_fastsdcpu_desc),
                            url = "https://github.com/ManuXD32/fastsdcpu"
                        )
                        LibraryItem(
                            name = "AUTOMATIC1111 / Stable Diffusion WebUI",
                            description = stringResource(R.string.about_sd_sketch_desc),
                            url = "https://github.com/ManuXD32/stable-diffusion-webui"
                        )
                        LibraryItem(
                            name = "Termux",
                            description = stringResource(R.string.about_termux_desc),
                            url = "https://github.com/termux"
                        )
                    }
                }
            }
            
            // Thank you
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.about_thanks),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DonationButton(
    emoji: String,
    text: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = emoji,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text)
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun LibraryItem(
    name: String,
    description: String,
    url: String
) {
    val context = LocalContext.current
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.action_open),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
