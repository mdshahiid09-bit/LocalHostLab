package com.localhostlab.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Code
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localhostlab.app.data.Project
import com.localhostlab.app.data.ProjectSourceType
import com.localhostlab.app.data.ProjectStatus
import com.localhostlab.app.ui.theme.StatusError
import com.localhostlab.app.ui.theme.StatusIdle
import com.localhostlab.app.ui.theme.StatusRunning
import com.localhostlab.app.ui.theme.StatusSuccess
import com.localhostlab.app.upload.ProjectTemplates
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onProjectClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEditor: (String) -> Unit
) {
    val projects by viewModel.projects.collectAsState()
    val isExtracting by viewModel.isExtracting.collectAsState()
    val importTitle by viewModel.importTitle.collectAsState()
    val extractionProgress by viewModel.extractionProgress.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var showNewProjectDialog by remember { mutableStateOf(false) }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.handleZipSelected(uri)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.handleFolderSelected(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "LocalHostLab",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (projects.isEmpty()) {
                EmptyProjectsState(
                    onPickFolderClick = { folderPickerLauncher.launch(null) },
                    onUploadZipClick = {
                        zipPickerLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream"
                            )
                        )
                    },
                    onNewProjectClick = { showNewProjectDialog = true }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Quick Action Bar at Top
                    item {
                        QuickActionBar(
                            onPickFolder = { folderPickerLauncher.launch(null) },
                            onUploadZip = {
                                zipPickerLauncher.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/octet-stream"
                                    )
                                )
                            },
                            onNewProject = { showNewProjectDialog = true }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // Project Cards
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onClick = { onProjectClick(project.id) },
                            onDelete = { viewModel.deleteProject(project.id) }
                        )
                    }
                }
            }

            // Extraction / Copy Progress Dialog
            if (isExtracting) {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    title = { Text(text = importTitle) },
                    text = {
                        Column {
                            val progress = extractionProgress
                            if (progress != null && progress.totalCount > 0) {
                                val frac = progress.extractedCount.toFloat() / progress.totalCount.toFloat()
                                LinearProgressIndicator(
                                    progress = frac,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Copying ${progress.extractedCount} of ${progress.totalCount} files",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = progress.currentFileName,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Scanning project files...",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                )
            }

            // Error Dialog (Checking stage validation or import failure)
            errorMessage?.let { error ->
                AlertDialog(
                    onDismissRequest = { viewModel.dismissError() },
                    title = {
                        Text(
                            text = error.title,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    },
                    text = { Text(text = error.reason) },
                    confirmButton = {
                        Button(onClick = { viewModel.dismissError() }) {
                            Text(text = "OK")
                        }
                    }
                )
            }

            // New Project Starter Template Dialog
            if (showNewProjectDialog) {
                NewProjectDialog(
                    onDismiss = { showNewProjectDialog = false },
                    onCreate = { name, template ->
                        showNewProjectDialog = false
                        viewModel.handleCreateNewProject(name, template) { newId ->
                            onOpenEditor(newId)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun QuickActionBar(
    onPickFolder: () -> Unit,
    onUploadZip: () -> Unit,
    onNewProject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Big Prominent Primary Button: Pick Folder
            Button(
                onClick = onPickFolder,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Pick React Project Folder",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onUploadZip,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Upload ZIP")
                }

                FilledTonalButton(
                    onClick = onNewProject,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New Project")
                }
            }
        }
    }
}

@Composable
fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val cardScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "cardBounce"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(cardScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = when (project.sourceType) {
                            ProjectSourceType.FOLDER -> Icons.Default.FolderOpen
                            ProjectSourceType.ZIP -> Icons.Default.FolderZip
                            ProjectSourceType.MANUAL -> Icons.Outlined.Code
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                StatusBadge(status = project.status)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Source: ${project.sourceType.getDisplayName()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (project.fileCount > 0) {
                    Text(
                        text = "${project.fileCount} files",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Path: ${project.path}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val lastRunText = if (project.lastRun > 0L) {
                    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                    "Last run: ${sdf.format(Date(project.lastRun))}"
                } else {
                    "Not run yet"
                }

                Text(
                    text = lastRunText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete Project",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: ProjectStatus) {
    val (bgColor, textColor, label) = when (status) {
        ProjectStatus.RUNNING -> Triple(StatusSuccess.copy(alpha = 0.2f), StatusSuccess, "Running")
        ProjectStatus.INSTALLING -> Triple(StatusRunning.copy(alpha = 0.2f), StatusRunning, "Installing...")
        ProjectStatus.BUILDING -> Triple(StatusRunning.copy(alpha = 0.2f), StatusRunning, "Building...")
        ProjectStatus.ERROR -> Triple(StatusError.copy(alpha = 0.2f), StatusError, "Error")
        ProjectStatus.IDLE -> Triple(StatusIdle.copy(alpha = 0.2f), StatusIdle, "Idle")
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun EmptyProjectsState(
    onPickFolderClick: () -> Unit,
    onUploadZipClick: () -> Unit,
    onNewProjectClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.size(80.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No projects yet",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Pick a React (Vite) project folder from your phone storage or upload a ZIP to run and preview on localhost.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Big Prominent Primary Button
        Button(
            onClick = onPickFolderClick,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = "Pick React Project Folder", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        }

        Spacer(modifier = Modifier.height(12.dp))

        FilledTonalButton(
            onClick = onUploadZipClick,
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(imageVector = Icons.Default.FolderZip, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Upload Project ZIP")
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onNewProjectClick,
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(imageVector = Icons.Default.NoteAdd, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "New Project (Starter Template)")
        }
    }
}

@Composable
fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, ProjectTemplates.TemplateType) -> Unit
) {
    var projectName by remember { mutableStateOf("my-react-app") }
    var selectedTemplate by remember { mutableStateOf(ProjectTemplates.TemplateType.REACT_TS) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New React Project", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    label = { Text("Project Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Choose Starter Template:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )

                ProjectTemplates.TemplateType.values().forEach { template ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedTemplate = template }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedTemplate == template),
                            onClick = { selectedTemplate = template }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = template.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(text = template.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (projectName.isNotBlank()) {
                        onCreate(projectName.trim(), selectedTemplate)
                    }
                }
            ) {
                Text("Create & Edit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
