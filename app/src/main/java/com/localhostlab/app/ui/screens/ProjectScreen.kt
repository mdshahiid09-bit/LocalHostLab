package com.localhostlab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localhostlab.app.data.ProjectStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    viewModel: ProjectViewModel,
    onBackClick: () -> Unit,
    onOpenPreview: (String) -> Unit
) {
    val context = LocalContext.current
    val project by viewModel.project.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val currentStep by viewModel.currentStepName.collectAsState()
    val stepProgress by viewModel.stepProgress.collectAsState()
    val lastFailure by viewModel.lastFailure.collectAsState()
    val isRunning by viewModel.isThisProjectRunning.collectAsState()
    val activePort by viewModel.activePort.collectAsState()
    val showSuccessSnackbar by viewModel.showSuccessSnackbar.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val logListState = rememberLazyListState()

    // Auto-scroll logs to bottom whenever new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logListState.animateScrollToItem(logs.size - 1)
        }
    }

    // Trigger success snackbar
    LaunchedEffect(showSuccessSnackbar, activePort) {
        if (showSuccessSnackbar && activePort != null) {
            snackbarHostState.showSnackbar(
                message = "HTTP Server successfully running at port $activePort!"
            )
            viewModel.dismissSnackbar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = project?.name ?: "Project",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Project Status & Path Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Status: ${project?.status?.getDisplayName() ?: "Unknown"}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        project?.status?.let { StatusBadge(status = it) }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Path: ${project?.path ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (project?.status == ProjectStatus.RUNNING && (activePort != null || project?.port != null)) {
                        val port = activePort ?: project?.port ?: 3000
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Local URL: http://127.0.0.1:$port/",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons: Run, Stop, Open preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.runProject() },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Run")
                }

                OutlinedButton(
                    onClick = { viewModel.stopProject() },
                    enabled = isRunning || project?.status == ProjectStatus.RUNNING,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stop")
                }

                FilledTonalButton(
                    onClick = {
                        project?.id?.let { onOpenPreview(it) }
                    },
                    enabled = project?.status == ProjectStatus.RUNNING,
                    modifier = Modifier.weight(1.3f)
                ) {
                    Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Preview")
                }
            }

            // Progress Indicator when step is executing
            if (currentStep != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = currentStep ?: "",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        stepProgress?.let {
                            Text(
                                text = "${(it * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    if (stepProgress != null) {
                        LinearProgressIndicator(
                            progress = stepProgress!!,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Log Console Header & Copy Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Build & Server Logs",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                TextButton(
                    onClick = { viewModel.copyLogs(context) },
                    enabled = logs.isNotEmpty()
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy log", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy log")
                }
            }

            // Log View: Read-only, monospace text, auto-scroll to bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF090D16), shape = RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "Logs will appear here once you click 'Run'...",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF64748B)
                        ),
                        modifier = Modifier.padding(8.dp)
                    )
                } else {
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(logs) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = if (line.contains("ERROR", ignoreCase = true)) {
                                        Color(0xFFF87171)
                                    } else if (line.contains("SUCCESS", ignoreCase = true)) {
                                        Color(0xFF34D399)
                                    } else {
                                        Color(0xFFE2E8F0)
                                    }
                                ),
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            // Failure Error Dialog (Step name, last 20 log lines, Retry, Copy log)
            val failure = lastFailure
            if (failure != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissFailure() },
                    title = {
                        Text(
                            text = "Failed at ${failure.stepName}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    text = {
                        Column {
                            Text(
                                text = "Last 20 log lines:",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .background(Color(0xFF090D16), RoundedCornerShape(6.dp))
                                    .padding(8.dp)
                            ) {
                                val scrollState = rememberScrollState()
                                Text(
                                    text = failure.last20Logs.joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color(0xFFE2E8F0)
                                    ),
                                    modifier = Modifier.horizontalScroll(scrollState)
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.dismissFailure()
                                viewModel.runProject()
                            }
                        ) {
                            Text("Retry")
                        }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = { viewModel.copyFailureLogs(context) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy log")
                            }
                            TextButton(onClick = { viewModel.dismissFailure() }) {
                                Text("Close")
                            }
                        }
                    }
                )
            }
        }
    }
}
