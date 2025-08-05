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

@Composable
fun NewSessionDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onCreateSession: (String) -> Unit
) {
    var routeName by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    
    val submitSession = {
        if (routeName.isNotBlank()) {
            onCreateSession(routeName.trim())
            routeName = ""
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