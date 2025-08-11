package com.monteslu.trailtracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onCreateSession: (String, Int) -> Unit
) {
    var routeName by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var selectedFrameSkip by remember { mutableStateOf(1) }
    var frameSkipExpanded by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    
    // Frame skip options: 1=every frame (30fps), 2=every other (15fps), etc., up to 30=every 30th (1fps)
    data class FrameOption(val skip: Int, val description: String, val fps: String)
    val frameOptions = listOf(
        FrameOption(1, "Every frame", "30 FPS"),
        FrameOption(2, "Every 2nd frame", "15 FPS"),
        FrameOption(3, "Every 3rd frame", "10 FPS"),
        FrameOption(5, "Every 5th frame", "6 FPS"),
        FrameOption(6, "Every 6th frame", "5 FPS"),
        FrameOption(10, "Every 10th frame", "3 FPS"),
        FrameOption(15, "Every 15th frame", "2 FPS"),
        FrameOption(30, "Every 30th frame", "1 FPS")
    )
    
    val selectedOption = frameOptions.find { it.skip == selectedFrameSkip } ?: frameOptions.first()
    
    val submitSession = {
        if (routeName.isNotBlank()) {
            onCreateSession(routeName.trim(), selectedFrameSkip)
            routeName = ""
            selectedFrameSkip = 1
            keyboardController?.hide()
        } else {
            showError = true
        }
    }
    
    if (isVisible) {
        Dialog(onDismissRequest = {
            keyboardController?.hide()
            onDismiss()
        }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "New Session",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    OutlinedTextField(
                        value = routeName,
                        onValueChange = { 
                            routeName = it
                            showError = false
                        },
                        label = { Text("Route Name") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = showError,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { 
                                submitSession()
                            }
                        ),
                        singleLine = true
                    )
                    
                    if (showError) {
                        Text(
                            text = "Please enter a route name",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    
                    // Frame Rate Dropdown
                    ExposedDropdownMenuBox(
                        expanded = frameSkipExpanded,
                        onExpandedChange = { frameSkipExpanded = !frameSkipExpanded },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        OutlinedTextField(
                            value = "${selectedOption.description} (${selectedOption.fps})",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Frame Rate") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = frameSkipExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        
                        ExposedDropdownMenu(
                            expanded = frameSkipExpanded,
                            onDismissRequest = { frameSkipExpanded = false }
                        ) {
                            frameOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { 
                                        Column {
                                            Text(option.description)
                                            Text(
                                                text = option.fps,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedFrameSkip = option.skip
                                        frameSkipExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                keyboardController?.hide()
                                onDismiss()
                            }
                        ) {
                            Text("Cancel")
                        }
                        
                        Button(
                            onClick = { submitSession() },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("Create")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ResumeSessionDialog(
    isVisible: Boolean,
    routes: List<String>,
    onDismiss: () -> Unit,
    onResumeSession: (String) -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    
    if (isVisible) {
        Dialog(onDismissRequest = {
            keyboardController?.hide()
            onDismiss()
        }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Resume Session",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    if (routes.isEmpty()) {
                        Text(
                            text = "No existing routes found",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(routes) { route ->
                                Card(
                                    onClick = {
                                        onResumeSession(route)
                                        onDismiss()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = route,
                                        modifier = Modifier.padding(16.dp),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }
                    }
                    
                    TextButton(
                        onClick = {
                            keyboardController?.hide()
                            onDismiss()
                        },
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 16.dp)
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}