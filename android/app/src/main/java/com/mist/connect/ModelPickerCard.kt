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
                Text(stringResource(R.string.picker_catalog_default, snapshot.hostProvider, snapshot.hostModel))
                Text(stringResource(R.string.picker_saved, snapshot.selection.provider ?: inherit, snapshot.selection.model ?: inherit, snapshot.selection.effort ?: inherit))
                Text(stringResource(if (ModelPickerContract.savedMatches(snapshot, selection)) R.string.picker_saved_match else R.string.picker_unsaved))
                OutlinedButton(onClick = { choosing = "provider" }, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.picker_provider, selection.provider ?: hostDefault))
                }
                OutlinedButton(onClick = { onSelection(selection.copy(provider = null, model = null)) }, enabled = !loading,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.picker_inherit_both)) }
                val provider = ModelPickerContract.provider(snapshot, selection)
                if (provider?.nonselectableAliases?.isNotEmpty() == true) {
                    Text(stringResource(R.string.picker_aliases, provider.nonselectableAliases.joinToString(" · ")))
                }
                Text(stringResource(R.string.picker_auth, stringResource(when (provider?.auth) {
                    "ready" -> R.string.picker_auth_ready
                    "not_ready" -> R.string.picker_auth_not_ready
                    else -> R.string.picker_unknown
                })))
                OutlinedButton(onClick = { choosing = "model" }, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.picker_model, selection.model ?: hostDefault))
                }
                val model = provider?.models?.singleOrNull { it.id == (selection.model ?: snapshot.hostModel) }
                // Contract-produced availability text remains raw; do not infer capability from it.
                Text(stringResource(R.string.picker_availability, ModelPickerContract.availabilityLabel(provider, model)))
                Text(stringResource(R.string.picker_reasoning, stringResource(when (model?.reasoning) {
                    true -> R.string.picker_reasoning_yes
                    false -> R.string.picker_reasoning_no
                    null -> R.string.picker_unknown
                })))
                OutlinedButton(onClick = { choosing = "effort" }, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.picker_effort, selection.effort ?: hostDefault))
                }
                Text(stringResource(R.string.picker_inheritance_note))
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
        val kind = choosing!!
        val row = ModelPickerContract.provider(snapshot, selection)
        val options: List<Pair<String?, String>> = listOf(null to stringResource(R.string.picker_inherit_host)) + when (kind) {
            "provider" -> snapshot.providers.map { it.id to (context.getString(R.string.picker_option_provider, it.id, context.getString(when(it.auth) {
                "ready" -> R.string.picker_option_auth_ready
                "not_ready" -> R.string.picker_option_auth_needed
                else -> R.string.picker_option_auth_unknown
            })) + if (it.freeTierPending == true) context.getString(R.string.picker_option_pending) else "") }
            "model" -> (row?.models ?: emptyList()).map { it.id to context.getString(R.string.picker_option_model, it.id, ModelPickerContract.availabilityLabel(row, it)) }
            else -> snapshot.efforts.map { it to context.getString(R.string.picker_option_effort, it) }
        }
        ModelPickerChoiceDialog(options, onDismiss = { choosing = null }) { exact ->
            onSelection(when (kind) {
                "provider" -> selection.copy(provider = exact)
                "model" -> selection.copy(model = exact)
                else -> selection.copy(effort = exact)
            }) // Keep other fields exactly; invalid combinations are visible, never silently reset.
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
