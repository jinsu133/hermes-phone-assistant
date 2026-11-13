package com.mist.connect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun ModelPickerCard(snapshot: ModelPickerSnapshot?, selection: ModelPickerSelection?, loading: Boolean,
    message: String, onRefresh: () -> Unit, onSelection: (ModelPickerSelection) -> Unit) {
    var choosing by remember { mutableStateOf<String?>(null) }
    var command by remember(snapshot, selection) { mutableStateOf<String?>(null) }
    var acceptedLimits by remember(snapshot, selection) { mutableStateOf(false) }
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.picker_heading), style = MaterialTheme.typography.titleMedium)
            Text(message)
            OutlinedButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (loading) R.string.picker_loading else R.string.picker_refresh))
            }
            if (snapshot != null && selection != null) {
                val inherit = stringResource(R.string.picker_inherit_short)
                val hostDefault = stringResource(R.string.picker_inherit_host)
                Text(stringResource(R.string.picker_revisions, snapshot.revision, snapshot.liveRevision?.toString() ?: stringResource(R.string.none)))
                Text(stringResource(if (snapshot.liveMatches) R.string.picker_live_match else R.string.picker_live_unknown))
                // The working model always follows the Hermes default profile; only effort is selectable here.
                Text(stringResource(R.string.picker_model_follows, snapshot.hostProvider, snapshot.hostModel),
                    style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.picker_saved_effort, snapshot.selection.effort ?: inherit))
                Text(stringResource(if (ModelPickerContract.savedMatches(snapshot, selection)) R.string.picker_saved_match else R.string.picker_unsaved))
                val provider = ModelPickerContract.provider(snapshot, selection)
                val model = provider?.models?.singleOrNull { it.id == snapshot.hostModel }
                Text(stringResource(R.string.picker_reasoning, stringResource(when (model?.reasoning) {
                    true -> R.string.picker_reasoning_yes
                    false -> R.string.picker_reasoning_no
                    null -> R.string.picker_unknown
                })))
                OutlinedButton(onClick = { choosing = "effort" }, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.picker_effort, selection.effort ?: hostDefault))
                }
                Text(stringResource(R.string.picker_effort_note))
                Text(stringResource(R.string.picker_strict_note))
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = acceptedLimits, enabled = !loading, onValueChange = { acceptedLimits = it })) {
                    Checkbox(checked = acceptedLimits, onCheckedChange = null, enabled = !loading)
                    Text(stringResource(R.string.picker_acknowledge), modifier = Modifier.weight(1f))
                }
                val error = ModelPickerContract.validationError(snapshot, selection)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                Button(onClick = { command = ModelPickerContract.operatorCommand(snapshot, selection) },
                    enabled = !loading && error == null && acceptedLimits, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.picker_make_command))
                }
                Text(stringResource(R.string.picker_off_note))
                Text(stringResource(if (snapshot.operatorWriteReady) R.string.picker_write_ready else R.string.picker_write_not_ready))
            }
        }
    }
    if (choosing != null && snapshot != null && selection != null) {
        val options: List<Pair<String?, String>> = listOf(null to stringResource(R.string.picker_inherit_host)) +
            snapshot.efforts.map { it to context.getString(R.string.picker_option_effort, it) }
        ModelPickerChoiceDialog(options, onDismiss = { choosing = null }) { exact ->
            // Provider/model stay null: the Hermes default profile chooses the model.
            onSelection(selection.copy(provider = null, model = null, effort = exact))
            choosing = null
        }
    }
    command?.let { text ->
        AlertDialog(onDismissRequest = { command = null }, title = { Text(stringResource(R.string.picker_command_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.picker_command_warning))
                SelectionContainer { Text(text) }
                Text(stringResource(R.string.picker_command_followup))
            } },
            confirmButton = { TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Hermes operator selection (not saved)", text))
                command = null
            }) { Text(stringResource(R.string.picker_copy)) } },
            dismissButton = { TextButton(onClick = { command = null }) { Text(stringResource(R.string.close)) } })
    }
}

@Composable
private fun ModelPickerChoiceDialog(options: List<Pair<String?, String>>, onDismiss: () -> Unit,
                                    onPick: (String?) -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.picker_choose)) }, text = {
        Column {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.picker_search)) }, singleLine = true)
            LazyColumn(Modifier.fillMaxWidth().height(280.dp)) {
                items(options.filter { it.second.contains(query, ignoreCase = true) }) { item ->
                    TextButton(onClick = { onPick(item.first) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(item.second) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}
