package com.localhostlab.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

class CodeSyntaxTransformation(private val fileExtension: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val builder = AnnotatedString.Builder(raw)

        val keywordColor = Color(0xFFF472B6)
        val stringColor = Color(0xFF4ADE80)
        val commentColor = Color(0xFF94A3B8)
        val tagColor = Color(0xFF38BDF8)
        val numberColor = Color(0xFFFB923C)

        // Comments
        Regex("//.*|/\\*[\\s\\S]*?\\*/").findAll(raw).forEach { match ->
            builder.addStyle(SpanStyle(color = commentColor), match.range.first, match.range.last + 1)
        }

        // Strings
        Regex("\"[^\"]*\"|'[^']*'|`[^`]*`").findAll(raw).forEach { match ->
            builder.addStyle(SpanStyle(color = stringColor), match.range.first, match.range.last + 1)
        }

        // Keywords
        val keywords = "\\b(import|export|from|default|function|const|let|var|return|if|else|for|while|class|interface|type|extends|implements|new|this|async|await|true|false|null|undefined|try|catch|finally)\\b"
        Regex(keywords).findAll(raw).forEach { match ->
            builder.addStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
        }

        // Numbers
        Regex("\\b\\d+(\\.\\d+)?\\b").findAll(raw).forEach { match ->
            builder.addStyle(SpanStyle(color = numberColor), match.range.first, match.range.last + 1)
        }

        // Tags for JSX/TSX/HTML
        if (fileExtension in listOf("tsx", "jsx", "html")) {
            Regex("</?[a-zA-Z0-9_-]+>?").findAll(raw).forEach { match ->
                builder.addStyle(SpanStyle(color = tagColor), match.range.first, match.range.last + 1)
            }
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBackClick: () -> Unit,
    onOpenPreview: (String) -> Unit
) {
    val fileTree by viewModel.fileTree.collectAsState()
    val openTabs by viewModel.openTabs.collectAsState()
    val activeIndex by viewModel.activeTabIndex.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<File?>(null) }
    var fileToRename by remember { mutableStateOf<File?>(null) }
    var newNameText by remember { mutableStateOf("") }
    var showFindReplaceBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }

    val activeTab = viewModel.activeTab

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissStatusMessage()
        }
    }

    // Intercept back button if there are unsaved files
    BackHandler {
        if (viewModel.hasUnsavedChanges()) {
            showUnsavedDialog = true
        } else {
            onBackClick()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Project Files",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Row {
                            IconButton(onClick = { showNewFileDialog = true }) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = "New File")
                            }
                            IconButton(onClick = { showNewFolderDialog = true }) {
                                Icon(imageVector = Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(fileTree, key = { it.file.canonicalPath }) { item ->
                            FileTreeRow(
                                item = item,
                                onClick = {
                                    if (!item.isDirectory) {
                                        viewModel.openFile(item.file)
                                        coroutineScope.launch { drawerState.close() }
                                    }
                                },
                                onRename = {
                                    fileToRename = item.file
                                    newNameText = item.file.name
                                },
                                onDelete = { fileToDelete = item.file }
                            )
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = activeTab?.relativePath ?: viewModel.project?.name ?: "Editor",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            ),
                            maxLines = 1
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (viewModel.hasUnsavedChanges()) {
                                showUnsavedDialog = true
                            } else {
                                onBackClick()
                            }
                        }) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(imageVector = Icons.Default.Menu, contentDescription = "Files")
                        }
                        IconButton(
                            onClick = { viewModel.undo() },
                            enabled = activeTab?.undoStack?.isNotEmpty() == true
                        ) {
                            Icon(imageVector = Icons.Default.Undo, contentDescription = "Undo")
                        }
                        IconButton(
                            onClick = { viewModel.redo() },
                            enabled = activeTab?.redoStack?.isNotEmpty() == true
                        ) {
                            Icon(imageVector = Icons.Default.Redo, contentDescription = "Redo")
                        }
                        IconButton(onClick = { showFindReplaceBar = !showFindReplaceBar }) {
                            Icon(imageVector = Icons.Default.FindReplace, contentDescription = "Find & Replace")
                        }
                        IconButton(
                            onClick = { viewModel.saveActiveFile() },
                            enabled = activeTab != null
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = "Save",
                                tint = if (activeTab?.isModified == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { onOpenPreview(viewModel.projectId) }) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Preview",
                                tint = MaterialTheme.colorScheme.primary
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
            ) {
                // Find & Replace Bar
                if (showFindReplaceBar && activeTab != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    label = { Text("Search") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = replaceQuery,
                                    onValueChange = { replaceQuery = it },
                                    label = { Text("Replace") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    if (searchQuery.isNotEmpty()) {
                                        val replaced = activeTab.content.replaceFirst(searchQuery, replaceQuery)
                                        viewModel.updateContent(replaced)
                                    }
                                }) {
                                    Text("Replace Once")
                                }
                                TextButton(onClick = {
                                    if (searchQuery.isNotEmpty()) {
                                        val replaced = activeTab.content.replace(searchQuery, replaceQuery)
                                        viewModel.updateContent(replaced)
                                    }
                                }) {
                                    Text("Replace All")
                                }
                            }
                        }
                    }
                }

                // Multi-Tab Bar
                if (openTabs.isNotEmpty()) {
                    ScrollableTabRow(
                        selectedTabIndex = activeIndex.coerceIn(0, openTabs.size - 1),
                        edgePadding = 8.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        openTabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = index == activeIndex,
                                onClick = { viewModel.selectTab(index) },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = (if (tab.isModified) "● " else "") + tab.file.name,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Normal,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close tab",
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable { viewModel.closeTab(index) }
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                // Editor Body: Line numbers + Code input
                if (activeTab != null) {
                    val ext = activeTab.file.extension.lowercase()
                    val transformation = remember(ext) { CodeSyntaxTransformation(ext) }

                    val lines = activeTab.content.lines()
                    val lineCount = lines.size.coerceAtLeast(1)

                    val verticalScroll = rememberScrollState()
                    val horizontalScroll = rememberScrollState()

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0F172A))
                    ) {
                        // Line numbers gutter
                        Column(
                            modifier = Modifier
                                .verticalScroll(verticalScroll)
                                .background(Color(0xFF1E293B))
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            for (i in 1..lineCount) {
                                Text(
                                    text = "$i",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        color = Color(0xFF64748B),
                                        lineHeight = 20.sp
                                    )
                                )
                            }
                        }

                        // Code Editor Field
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(verticalScroll)
                                .horizontalScroll(horizontalScroll)
                                .padding(12.dp)
                        ) {
                            BasicTextField(
                                value = activeTab.content,
                                onValueChange = { newText ->
                                    viewModel.updateContent(newText)
                                },
                                textStyle = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    color = Color(0xFFF1F5F9),
                                    lineHeight = 20.sp
                                ),
                                cursorBrush = SolidColor(Color(0xFF38BDF8)),
                                visualTransformation = transformation,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No files open", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(12.dp))
                            FilledTonalButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                Icon(imageVector = Icons.Default.Menu, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Browse Project Files")
                            }
                        }
                    }
                }
            }

            // Dialogs
            if (showUnsavedDialog) {
                AlertDialog(
                    onDismissRequest = { showUnsavedDialog = false },
                    title = { Text("Unsaved Changes") },
                    text = { Text("You have unsaved changes in your project. Do you want to discard them and exit?") },
                    confirmButton = {
                        Button(onClick = {
                            showUnsavedDialog = false
                            onBackClick()
                        }) {
                            Text("Discard & Exit")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            viewModel.saveActiveFile()
                            showUnsavedDialog = false
                            onBackClick()
                        }) {
                            Text("Save & Exit")
                        }
                    }
                )
            }

            if (showNewFileDialog) {
                var newFilePath by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { showNewFileDialog = false },
                    title = { Text("New File") },
                    text = {
                        OutlinedTextField(
                            value = newFilePath,
                            onValueChange = { newFilePath = it },
                            label = { Text("e.g. src/components/Button.tsx") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (newFilePath.isNotBlank()) {
                                viewModel.createFile(newFilePath.trim())
                                showNewFileDialog = false
                            }
                        }) {
                            Text("Create")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNewFileDialog = false }) { Text("Cancel") }
                    }
                )
            }

            if (showNewFolderDialog) {
                var newFolderPath by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { showNewFolderDialog = false },
                    title = { Text("New Folder") },
                    text = {
                        OutlinedTextField(
                            value = newFolderPath,
                            onValueChange = { newFolderPath = it },
                            label = { Text("e.g. src/components") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (newFolderPath.isNotBlank()) {
                                viewModel.createFolder(newFolderPath.trim())
                                showNewFolderDialog = false
                            }
                        }) {
                            Text("Create")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancel") }
                    }
                )
            }

            fileToDelete?.let { file ->
                AlertDialog(
                    onDismissRequest = { fileToDelete = null },
                    title = { Text("Delete Confirmation") },
                    text = { Text("Are you sure you want to permanently delete '${file.name}'?") },
                    confirmButton = {
                        Button(onClick = {
                            viewModel.deleteFile(file)
                            fileToDelete = null
                        }) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { fileToDelete = null }) { Text("Cancel") }
                    }
                )
            }

            fileToRename?.let { file ->
                AlertDialog(
                    onDismissRequest = { fileToRename = null },
                    title = { Text("Rename") },
                    text = {
                        OutlinedTextField(
                            value = newNameText,
                            onValueChange = { newNameText = it },
                            label = { Text("New name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (newNameText.isNotBlank()) {
                                viewModel.renameFile(file, newNameText.trim())
                                fileToRename = null
                            }
                        }) {
                            Text("Rename")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { fileToRename = null }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}

@Composable
fun FileTreeRow(
    item: FileItem,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = (item.depth * 12).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Outlined.Description,
            contentDescription = null,
            tint = if (item.isDirectory) Color(0xFF38BDF8) else Color(0xFF94A3B8),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace
            ),
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        IconButton(onClick = onRename, modifier = Modifier.size(24.dp)) {
            Icon(imageVector = Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(14.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
        }
    }
}
