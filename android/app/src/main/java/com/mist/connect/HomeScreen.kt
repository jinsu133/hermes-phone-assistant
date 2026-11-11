package com.mist.connect

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mist.connect.ui.theme.MistConnectTheme

/** Stateless renderer: state/recomposition/restore cannot call commands. */
@Composable
internal fun HomeScreen(state: CompanionUiState, diagnostics: String,
                        actions: List<HomeAction>, onCommand: (HomeCommand) -> Unit) {
    MistConnectTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("HERMES", color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.home_heading), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() })
                CompanionStatusCard(state)
                // Same command membership/order and callbacks; only visual priority changes.
                val primary = actions.filter { it.section == HomeSection.PRIMARY }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    primary.filter { it.command == HomeCommand.LISTEN_ON || it.command == HomeCommand.LISTEN_OFF }
                        .forEach { action ->
                            HomeActionButton(action, onCommand, Modifier.weight(1f),
                                emphasized = (action.command == HomeCommand.LISTEN_ON &&
                                    state.phase == UiPhase.OFF_CONFIRMED && state.evidence == UiEvidence.OWNER_SNAPSHOT &&
                                    !state.offPending && state.blocked != UiBlocked.BLOCKED) ||
                                    (action.command == HomeCommand.LISTEN_OFF &&
                                    state.listening == UiListening.ENABLED_CAPTURE_UNKNOWN && !state.offPending))
                        }
                }
                primary.filter { it.command != HomeCommand.LISTEN_ON && it.command != HomeCommand.LISTEN_OFF }
                    .forEach { action ->
                        TextButton(onClick = { onCommand(action.command) }, modifier = Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp).testTag("home-${action.command.name}")) { Text(action.title) }
                    }
                HomeSection.entries.filter { it != HomeSection.PRIMARY }.forEach { section ->
                    var expanded by rememberSaveable(section.name) { mutableStateOf(section == HomeSection.EVERYDAY) }
                    val name = sectionTitle(section)
                    val toggle = if (expanded) stringResource(R.string.section_collapse, name)
                        else stringResource(R.string.section_expand, name)
                    HorizontalDivider()
                    TextButton(onClick = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                            stateDescription = toggle
                        }) { Text(toggle, modifier = Modifier.fillMaxWidth()) }
                    if (expanded) {
                        if (section == HomeSection.DIAGNOSTICS) {
                            Text(stringResource(R.string.diagnostics_heading), style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.semantics { heading() })
                            Text(stringResource(R.string.home_explanation), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.diagnostics_service_note, state.serviceMessage))
                            Text(CompanionUiText.details(LocalContext.current, state))
                            Text(diagnostics)
                        }
                        actions.filter { it.section == section }.forEach { action -> HomeActionButton(action, onCommand) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompanionStatusCard(state: CompanionUiState) {
    val context = LocalContext.current
    val title = CompanionUiText.title(context, state)
    val hint = CompanionUiText.nextStep(context, state)
    val actualBlocker = state.blocked == UiBlocked.BLOCKED ||
        state.service?.blocker == ServiceUiPublication.Blocker.CLEANUP_UNCERTAIN
    val positive = state.evidence == UiEvidence.OWNER_SNAPSHOT && !state.offPending &&
        state.phase == UiPhase.OFF_CONFIRMED && !actualBlocker
    val indicator = when {
        actualBlocker -> MaterialTheme.colorScheme.error
        positive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(Modifier.fillMaxWidth().testTag("companion-status").semantics {
        contentDescription = context.getString(R.string.status_accessibility, title, hint)
    }, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top) {
            Surface(Modifier.padding(top = 6.dp).size(8.dp), shape = CircleShape, color = indicator) {}
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    color = if (actualBlocker) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HomeActionButton(action: HomeAction, onCommand: (HomeCommand) -> Unit,
                             modifier: Modifier = Modifier.fillMaxWidth(), emphasized: Boolean = false) {
    val target = modifier.heightIn(min = 48.dp).testTag("home-${action.command.name}")
    if (emphasized) Button(onClick = { onCommand(action.command) }, modifier = target) { Text(action.title) }
    else OutlinedButton(onClick = { onCommand(action.command) }, modifier = target) { Text(action.title) }
}

@Composable
private fun sectionTitle(section: HomeSection): String = stringResource(when (section) {
    HomeSection.PRIMARY -> R.string.section_primary
    HomeSection.EVERYDAY -> R.string.section_everyday
    HomeSection.MANUAL -> R.string.section_manual
    HomeSection.CONNECTION -> R.string.section_connection
    HomeSection.DIAGNOSTICS -> R.string.section_diagnostics
    HomeSection.ADVANCED -> R.string.section_advanced
})
