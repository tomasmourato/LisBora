package pt.lisbora.feature.about

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.lisbora.BuildConfig
import pt.lisbora.R

/** The project's public pages: the privacy policy and the code, where problems are reported. */
object AboutLinks {
    const val PRIVACY = "https://github.com/tomasmourato/LisBora/blob/main/docs/PRIVACIDADE.md"
    const val CODE = "https://github.com/tomasmourato/LisBora"
    const val OSM = "https://www.openstreetmap.org/copyright"
    const val CYCLE_NETWORK = "https://dados.cm-lisboa.pt/dataset/rede-ciclavel"
}

/**
 * "Sobre a LisBora": the version, what it does with the rider's data (and the privacy policy), where the
 * data comes from with its licences, and the open-source licences in full.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    var notices by rememberSaveable { mutableStateOf(false) }
    // Full screen, edge to edge: the Scaffolds keep their content clear of the system bars.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The dialog has its own window: dark status and navigation bar icons on the light theme.
        val view = LocalView.current
        val light = !isSystemInDarkTheme()
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
        if (notices) {
            NoticesScreen(onBack = { notices = false })
        } else {
            AboutScreen(onBack = onDismiss, onNotices = { notices = true })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onNotices: () -> Unit) {
    val uri = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_summary), style = MaterialTheme.typography.bodyLarge)
            Link(R.string.about_privacy) { uri.openUri(AboutLinks.PRIVACY) }
            Link(R.string.about_code) { uri.openUri(AboutLinks.CODE) }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Heading(R.string.about_data)
            Source(R.string.about_data_osm_title, R.string.about_data_osm)
            Link(R.string.about_data_osm_link) { uri.openUri(AboutLinks.OSM) }
            Source(R.string.about_data_cml_title, R.string.about_data_cml)
            Link(R.string.about_data_cml_link) { uri.openUri(AboutLinks.CYCLE_NETWORK) }
            Source(R.string.about_data_elevation_title, R.string.about_data_elevation)
            Source(R.string.about_data_basemap_title, R.string.about_data_basemap)
            Source(R.string.about_data_gira_title, R.string.about_data_gira)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Heading(R.string.about_software)
            Text(stringResource(R.string.about_software_summary), style = MaterialTheme.typography.bodyMedium)
            Link(R.string.about_notices, onClick = onNotices)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                stringResource(R.string.about_safety),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

/** The licences in full, from `assets/licenses/NOTICES.txt`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoticesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val text by produceState("") {
        value = withContext(Dispatchers.IO) { reflow(context.assets.open(NOTICES).bufferedReader().use { it.readText() }) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_notices)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Heading(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Source(@StringRes title: Int, @StringRes body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Link(@StringRes text: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(text), modifier = Modifier.fillMaxWidth())
    }
}

private const val NOTICES = "licenses/NOTICES.txt"

/**
 * Joins the hard-wrapped lines of each paragraph of a licence text, so it wraps to the screen. List
 * items keep their own lines, and a rule of "=" between licences becomes a short one.
 */
internal fun reflow(text: String): String =
    text.replace("\r\n", "\n").split(PARAGRAPH_BREAK).mapNotNull { paragraph ->
        val lines = paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() }
        when {
            lines.isEmpty() -> null
            lines.all { line -> line.all { it == '=' } } -> RULE
            else -> buildString {
                lines.forEachIndexed { i, line ->
                    if (i > 0) append(if (LIST_ITEM.matches(line)) '\n' else ' ')
                    append(line)
                }
            }
        }
    }.joinToString("\n\n")

private val PARAGRAPH_BREAK = Regex("\n[ \t]*\n")
private val LIST_ITEM = Regex("""^([-*•]|\d+\.|\([a-z0-9]+\))\s.*""")
private const val RULE = "· · ·"
