package com.localhostlab.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localhostlab.app.data.ProjectSourceType
import com.localhostlab.app.data.ProjectStatus
import com.localhostlab.app.ui.components.ConfettiView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    viewModel: ProjectViewModel,
    onBackClick: () -> Unit,
    onOpenPreview: (String) -> Unit,
    onOpenEditor: (String) -> Unit
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
    val showConfetti by viewModel.showConfetti.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isReSyncing by viewModel.isReSyncing.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val logListState = rememberLazyListState()

    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    val resyncPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.reSyncFromUri(context, uri)
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logListState.animateScrollToItem(logs.size - 1)
        }
    }

    LaunchedEffect(showSuccessSnackbar, activePort) {
        if (showSuccessSnackbar && activePort != null) {
            snackbarHostState.showSnackbar("HTTP Server successfully running at port $activePort!")
            viewModel.dismissSnackbar()
        }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissStatusMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    actions = {
                        IconButton(onClick = { onOpenEditor(viewModel.projectId) }) {
                            Icon(imageVector = Icons.Default.Code, contentDescription = "Edit Code")
                        }
                        IconButton(onClick = { viewModel.shareProject(context) }) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(imageVector = Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Rename Project") },
                                onClick = {
                                    showMenu = false
                                    renameText = project?.name ?: ""
                                    showRenameDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) }
                            )
                            if (project?.sourceType == ProjectSourceType.FOLDER) {
                                DropdownMenuItem(
                                    text = { Text("Re-sync Folder") },
                                    onClick = {
                                        showMenu = false
                                        val savedUri = project?.sourceUri
                                        if (savedUri != null) {
                                            viewModel.reSyncFromUri(context, Uri.parse(savedUri))
                                        } else {
                                            resyncPickerLauncher.launch(null)
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Export ZIP") },
                                onClick = {
                                    showMenu = false
                                    viewModel.exportProjectZip(context)
                                },
                                leadingIcon = { Icon(Icons.Default.FolderZip, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete Project", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
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
                // Project Status & Info Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                project?.status?.let { StatusBadge(status = it) }
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = project?.sourceType?.getDisplayName() ?: "Project",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            if (project?.fileCount != null && project?.fileCount != 0) {
                                Text(
                                    text = "${project?.fileCount} files",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Path: ${project?.path ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (project?.status == ProjectStatus.RUNNING && (activePort != null || project?.port != null)) {
                            val port = activePort ?: project?.port ?: 3000
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Localhost URL: http://127.0.0.1:$port/",
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

                // Action Buttons: Run, Stop, Preview
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
                        onClick = { onOpenPreview(viewModel.projectId) },
                        enabled = (isRunning || project?.status == ProjectStatus.RUNNING) && activePort != null && project?.status != ProjectStatus.ERROR,
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Preview")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Progress Indicator
                if (currentStep != null) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = currentStep ?: "",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            stepProgress?.let {
                                Text(
                                    text = "${(it * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        if (stepProgress != null) {
                            LinearProgressIndicator(
                                progress = stepProgress ?: 0f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }

                // Terminal Logs Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Build & Server Console",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )

                    Row {
                        IconButton(onClick = { viewModel.copyLogs(context) }, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy log", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Read-only Monospace Terminal
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF0F172A), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "Ready to start. Tap 'Run' to install packages, build, and launch server.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF64748B)
                            )
                        )
                    } else {
                        LazyColumn(
                            state = logListState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(logs) { line ->
                                val color = when {
                                    line.startsWith("[LocalHostLab] SUCCESS") -> Color(0xFF4ADE80)
                                    line.startsWith("[LocalHostLab] Failed") || "npm ERR!" in line -> Color(0xFFF87171)
                                    line.startsWith("[LocalHostLab]") -> Color(0xFF38BDF8)
                                    else -> Color(0xFFE2E8F0)
                                }
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    ),
                                    color = color
                                )
                            }
                        }
                    }
                }
            }
        }

        // Confetti celebration animation on success
        if (showConfetti) {
            ConfettiView(
                durationMs = 3500,
                onFinished = { viewModel.dismissConfetti() }
            )
        }
    }

    // Failure Dialog with Step Name, Last 20 lines, and Fix Suggestion
    lastFailure?.let { failure ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissFailureDialog() },
            title = {
                Text(text = "Build Failed: ${failure.stepName}", color = MaterialTheme.colorScheme.error)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Fix Suggestion Box
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lightbulb,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Fix Suggestion",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = failure.fixSuggestion,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Last 20 log output lines:",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val horizontalScroll = rememberScrollState()
                    val verticalScroll = rememberScrollState()

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                            .horizontalScroll(horizontalScroll)
                            .verticalScroll(verticalScroll)
                    ) {
                        Text(
                            text = failure.last20Logs.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color(0xFFFCA5A5)
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissFailureDialog()
                        viewModel.runProject()
                    }
                ) {
                    Text(text = "Retry")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.copyFailureLogs(context) }
                ) {
                    Text(text = "Copy Log")
                }
            }
        )
    }

    // Rename Dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Project") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Project Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.renameProject(renameText)
                    showRenameDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Project", color = MaterialTheme.colorScheme.error) },
            text = { Text("Are you sure you want to permanently delete '${project?.name}'? All files and logs will be removed.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteProject { onBackClick() }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}
