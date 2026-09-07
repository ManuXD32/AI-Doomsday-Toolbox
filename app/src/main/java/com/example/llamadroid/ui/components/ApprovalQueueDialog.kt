package com.example.llamadroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import com.example.llamadroid.service.StagedFileCache
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R

/**
 * ApprovalQueueDialog - Shows pending file writes for user approval
 */
@Composable
fun ApprovalQueueDialog(
    onDismiss: () -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onApproveAll: () -> Unit,
    onDenyAll: () -> Unit
) {
    val stagedFiles by StagedFileCache.stagedFiles.collectAsState()
    val pendingList = stagedFiles.values.sortedBy { it.timestamp }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.approval_title, pendingList.size),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_dismiss))
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                if (pendingList.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("✅", fontSize = 48.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.approval_no_pending), color = Color.Gray)
                        }
                    }
                } else {
                    // Batch actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDenyAll,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.approval_deny_all))
                        }
                        Button(
                            onClick = onApproveAll,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4CAF50)
                            )
                        ) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.approval_approve_all))
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // File list
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(pendingList, key = { it.id }) { staged ->
                            StagedFileCard(
                                stagedFile = staged,
                                onApprove = { onApprove(staged.path) },
                                onDeny = { onDeny(staged.path) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StagedFileCard(
    stagedFile: StagedFileCache.StagedFile,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val isNewFile = stagedFile.originalContent == null
    
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isNewFile) 
                Color(0xFF1B5E20).copy(alpha = 0.2f) 
            else 
                Color(0xFFE65100).copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // File path and status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (isNewFile) stringResource(R.string.approval_new_file) else stringResource(R.string.approval_modify_file),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isNewFile) Color(0xFF4CAF50) else Color(0xFFFF9800)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stagedFile.agentRole ?: "",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }
                    Text(
                        stagedFile.path.substringAfterLast("/"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        stagedFile.path,
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Expandable content preview
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(8.dp)
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (expanded) stringResource(R.string.approval_hide_content) else stringResource(R.string.approval_view_content, stagedFile.content.length))
            }
            
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                
                if (!isNewFile && stagedFile.originalContent != null) {
                    // Show diff for modified files
                    Text(stringResource(R.string.approval_original_label), fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFFEF5350))
                    Surface(
                        color = Color.Black.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionContainer {
                            Text(
                                stagedFile.originalContent.take(500) + if (stagedFile.originalContent.length > 500) "\n..." else "",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color(0xFFEF5350),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.approval_new_label), fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF66BB6A))
                }
                
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                ) {
                    SelectionContainer {
                        Text(
                            stagedFile.content.take(2000) + if (stagedFile.content.length > 2000) "\n... (${stagedFile.content.length} total)" else "",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = if (isNewFile) Color.White else Color(0xFF66BB6A),
                            modifier = Modifier
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Approve/Deny buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.approval_deny_btn), fontSize = 12.sp)
                }
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.approval_approve_btn), fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Badge showing pending approval count
 */
@Composable
fun PendingApprovalBadge(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (count > 0) {
        Badge(
            modifier = modifier,
            containerColor = Color(0xFFFF9800)
        ) {
            Text(
                count.toString(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
