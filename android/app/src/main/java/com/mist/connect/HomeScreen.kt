package com.mist.connect

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import com.mist.connect.ui.theme.MistConnectTheme

/** Stateless renderer: state/recomposition/restore cannot call commands. */
@Composable
internal fun HomeScreen(state: CompanionUiState, diagnostics: String,
                        actions: List<HomeAction>, onCommand: (HomeCommand) -> Unit) {
    MistConnectTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val byCommand = actions.associateBy { it.command }
                val shown = mutableSetOf<HomeCommand>()
                HomeHeader()
                CompanionStatusCard(state, onFix = { command ->
                    if (byCommand.containsKey(command)) onCommand(command)
                }, fixAvailable = { byCommand.containsKey(it) })
                ListenControls(state, byCommand, onCommand)
                shown += HomeCommand.LISTEN_ON
                shown += HomeCommand.LISTEN_OFF

                ReadinessCard(state, byCommand, onCommand)
                byCommand[HomeCommand.PERMISSIONS_OVERVIEW]?.let { shown += it.command }

                val quick = listOf(HomeCommand.RECENT_REPLY, HomeCommand.PIN_WIDGET, HomeCommand.OVERLAY_SETTINGS, HomeCommand.THEME)
                    .mapNotNull { byCommand[it] }
                if (quick.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.home_quick))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        quick.forEach { action ->
                            QuickTile(action, Modifier.weight(1f), onCommand)
                            shown += action.command
                        }
                    }
                }

                val stop = byCommand[HomeCommand.STOP_EVERYTHING]
                if (stop != null) shown += stop.command
                // Remaining primary actions keep their place ahead of the grouped sections.
                actions.filter { it.section == HomeSection.PRIMARY && it.command !in shown }.forEach { action ->
                    HomeActionButton(action, onCommand)
                    shown += action.command
                }

                SectionLabel(stringResource(R.string.home_more))
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()) {
                    Column {
                        val sections = HomeSection.entries.filter { it != HomeSection.PRIMARY }
                        sections.forEachIndexed { index, section ->
                            val members = actions.filter { it.section == section && it.command !in shown }
                            if (members.isEmpty() && section != HomeSection.DIAGNOSTICS) return@forEachIndexed
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ExpandableSection(section, state, diagnostics, members, onCommand)
                        }
                    }
                }

                if (stop != null) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(onClick = { onCommand(stop.command) },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("home-${stop.command.name}")) {
                            Text(stop.title, fontWeight = FontWeight.SemiBold)
                        }
                        Text(stringResource(R.string.home_stop_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // Headset and outline take the current theme's colours.
        val context = LocalContext.current
        val theme = AppTheme.state.value
        val sizePx = with(LocalDensity.current) { 64.dp.roundToPx() }
        val portrait = remember(theme, sizePx) { MascotArt.portraitBitmap(context, sizePx, theme.palette).asImageBitmap() }
        Image(portrait, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(64.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Hermes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.5.sp)
            Text(stringResource(R.string.home_heading), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        }
    }
}

private data class ToneColors(val container: Color, val accent: Color, val onContainer: Color)

@Composable
private fun toneColors(tone: HomeTone): ToneColors {
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        HomeTone.READY -> ToneColors(scheme.surface, Color(0xFF4A9B6E), scheme.onSurface)
        HomeTone.ACTIVE -> ToneColors(scheme.surface, Color(0xFF1FA89A), scheme.onSurface)
        HomeTone.ATTENTION -> ToneColors(scheme.surface, Color(0xFFEF8A30), scheme.onSurface)
        HomeTone.PROBLEM -> ToneColors(scheme.errorContainer, scheme.error, scheme.onErrorContainer)
        HomeTone.NEUTRAL -> ToneColors(scheme.surface, scheme.outline, scheme.onSurface)
    }
}

@Composable
internal fun CompanionStatusCard(state: CompanionUiState, onFix: (HomeCommand) -> Unit = {},
                                 fixAvailable: (HomeCommand) -> Boolean = { false }) {
    val context = LocalContext.current
    val title = CompanionUiText.title(context, state)
    val reason = HomeStatus.blockerText(state)?.let { context.getString(it) }
    val hint = reason ?: CompanionUiText.nextStep(context, state)
    val tone = HomeStatus.tone(state)
    val colors = toneColors(tone)
    val pulse = rememberInfiniteTransition(label = "status-pulse")
    val glow by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "glow")
    Surface(Modifier.fillMaxWidth().testTag("companion-status").semantics {
        contentDescription = context.getString(R.string.status_accessibility, title, hint)
    }, color = colors.container, shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, colors.accent.copy(alpha = 0.55f))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(22.dp)) {
                    if (tone == HomeTone.ACTIVE) Box(Modifier.size(22.dp).scale(glow).alpha(0.35f)
                        .background(colors.accent, CircleShape))
                    Box(Modifier.size(12.dp).background(colors.accent, CircleShape))
                }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    color = colors.onContainer)
            }
            Text(hint, style = MaterialTheme.typography.bodyLarge, color = colors.onContainer.copy(alpha = 0.85f))
            val fix = HomeStatus.fix(state)
            if (fix != null && fixAvailable(fix.command)) {
                FilledTonalButton(onClick = { onFix(fix.command) },
                    modifier = Modifier.heightIn(min = 44.dp).testTag("fix-${fix.command.name}")) {
                    Text(stringResource(fix.text))
                }
            }
        }
    }
}

@Composable
private fun ListenControls(state: CompanionUiState, byCommand: Map<HomeCommand, HomeAction>,
                           onCommand: (HomeCommand) -> Unit) {
    val on = byCommand[HomeCommand.LISTEN_ON]
    val off = byCommand[HomeCommand.LISTEN_OFF]
    if (on == null && off == null) return
    val active = HomeStatus.listeningActive(state)
    val main = if (active) off ?: on else on ?: off
    val other = if (main === on) off else on
    main!!
    Button(onClick = { onCommand(main.command) },
        shape = RoundedCornerShape(20.dp),
        colors = if (main.command == HomeCommand.LISTEN_OFF)
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
        else ButtonDefaults.buttonColors(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("home-${main.command.name}")) {
        Text(main.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    if (other != null) {
        OutlinedButton(onClick = { onCommand(other.command) }, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("home-${other.command.name}")) {
            Text(other.title)
        }
    }
    Text(stringResource(R.string.home_tagline, WakeSettings.wakePhrase()), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth())
    byCommand[HomeCommand.WAKE_SETTINGS]?.let { words ->
        TextButton(onClick = { onCommand(words.command) },
            modifier = Modifier.fillMaxWidth().testTag("home-${words.command.name}")) { Text(words.title) }
    }
}

@Composable
private fun ReadinessCard(state: CompanionUiState, byCommand: Map<HomeCommand, HomeAction>,
                          onCommand: (HomeCommand) -> Unit) {
    val items = HomeStatus.readiness(state)
    SectionLabel(stringResource(R.string.home_readiness))
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant)
                val fix = item.fix?.takeIf { byCommand.containsKey(it) }
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .then(if (fix != null) Modifier.clickable { onCommand(fix) } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("ready-${index}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReadyBadge(item.level)
                    Text(stringResource(item.label), style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f))
                    Text(stringResource(item.value), style = MaterialTheme.typography.bodyMedium,
                        color = when (item.level) {
                            ReadyLevel.OK -> Color(0xFF3F8A5F)
                            ReadyLevel.MISSING -> MaterialTheme.colorScheme.error
                            ReadyLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                        })
                    if (fix != null) Text("›", style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val setup = byCommand[HomeCommand.PERMISSIONS_OVERVIEW]
            if (setup != null) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(onClick = { onCommand(setup.command) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp)
                        .testTag("home-${setup.command.name}")) {
                    Text(if (items.any { it.level == ReadyLevel.MISSING }) stringResource(R.string.home_first_setup)
                        else setup.title)
                }
                Text(stringResource(R.string.permission_overview_intro), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            }
        }
    }
}

@Composable
private fun ReadyBadge(level: ReadyLevel) {
    val (bg, glyph) = when (level) {
        ReadyLevel.OK -> Color(0xFF4A9B6E) to "✓"
        ReadyLevel.MISSING -> MaterialTheme.colorScheme.error to "!"
        ReadyLevel.UNKNOWN -> MaterialTheme.colorScheme.outline to "?"
    }
    Box(Modifier.size(26.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text(glyph, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun QuickTile(action: HomeAction, modifier: Modifier, onCommand: (HomeCommand) -> Unit) {
    Surface(onClick = { onCommand(action.command) }, shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.heightIn(min = 72.dp).testTag("home-${action.command.name}")) {
        Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
            val label = when (action.command) {
                HomeCommand.PIN_WIDGET -> stringResource(R.string.quick_widget)
                HomeCommand.OVERLAY_SETTINGS -> stringResource(R.string.quick_overlay)
                HomeCommand.THEME -> stringResource(R.string.quick_theme)
                else -> action.title
            }
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() })
}

@Composable
private fun ExpandableSection(section: HomeSection, state: CompanionUiState, diagnostics: String,
                              members: List<HomeAction>, onCommand: (HomeCommand) -> Unit) {
    var expanded by rememberSaveable(section.name) { mutableStateOf(false) }
    val name = sectionTitle(section)
    val toggle = if (expanded) stringResource(R.string.section_collapse, name)
        else stringResource(R.string.section_expand, name)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { expanded = !expanded }
        .padding(horizontal = 16.dp).semantics { stateDescription = toggle },
        verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(if (expanded) "⌃" else "⌄", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (section == HomeSection.DIAGNOSTICS) {
                Text(stringResource(R.string.diagnostics_heading), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.home_explanation, WakeSettings.stopPhrase()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.diagnostics_service_note, state.serviceMessage),
                    style = MaterialTheme.typography.bodySmall)
                Text(CompanionUiText.details(LocalContext.current, state), style = MaterialTheme.typography.bodySmall)
                Text(diagnostics, style = MaterialTheme.typography.bodySmall)
            }
            members.forEach { action -> HomeActionButton(action, onCommand) }
        }
    }
}

@Composable
private fun HomeActionButton(action: HomeAction, onCommand: (HomeCommand) -> Unit,
                             modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedButton(onClick = { onCommand(action.command) }, shape = RoundedCornerShape(14.dp),
        modifier = modifier.heightIn(min = 48.dp).testTag("home-${action.command.name}")) {
        Text(action.title, textAlign = TextAlign.Center)
    }
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
