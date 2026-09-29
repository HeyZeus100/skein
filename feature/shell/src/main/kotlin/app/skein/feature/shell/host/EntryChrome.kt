// skein-xtov.24.8 (AL-09a): what every re-hosted entry shares — its top bar's
// navigation icon (ADAPTIVE_LAYOUT_SPEC.md §3.5), open by kind (IA §2
// principle 2, LC-20) and the gone state (OBJECT_LIFECYCLE_SPEC.md §3.5).
// Feature entries depend on this module, never on one another's entries.
package app.skein.feature.shell.host

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import app.skein.core.designsystem.components.SkeinAction
import app.skein.core.designsystem.components.SkeinEmptyState
import app.skein.core.designsystem.components.SkeinTopAppBar
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinSpacing
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.VaultRepository
import app.skein.core.navigation.ChatContextKey
import app.skein.core.navigation.ConnectionsKey
import app.skein.core.navigation.Destination
import app.skein.core.navigation.NavMode
import app.skein.core.navigation.ObjectKind
import app.skein.core.navigation.OpenMode
import app.skein.core.navigation.PaneRole
import app.skein.core.navigation.SkeinKey
import app.skein.core.navigation.TransientKey
import app.skein.core.navigation.TransientKind
import app.skein.core.navigation.destination
import app.skein.core.navigation.role
import app.skein.feature.shell.container.LocalSkeinDrawerOpener

/** The navigation icon at the start of an entry's top bar (§3.5). */
enum class EntryNavIcon { MENU, BACK, CLOSE, NONE }

internal val LocalEntryIsList = staticCompositionLocalOf { false }

object EntryChromeTestTags {
    const val NAV_ICON = "skein_entry_nav_icon"
    const val GONE = "skein_entry_gone"
    const val LIST_TOGGLE = "skein_entry_list_toggle"
}

/**
 * §3.5: ☰ on a Phone root (and on a Phone conversation: the drawer is the chat
 * switcher); none on a rail root or on a detail shown beside its list; ✕ on a
 * sheet and on an extra pane beside its detail; ← on everything pushed full-screen.
 */
@Composable
fun SkeinShellState.navIconFor(key: SkeinKey): EntryNavIcon {
    val layout = LocalSkeinWindowLayout.current
    val mode = layout.navMode()
    val phone = mode == NavMode.PHONE
    val panes = layout.maxPanes > 1
    val sheet =
        key is ChatContextKey || key is ConnectionsKey || (key is TransientKey && key.kind == TransientKind.CONNECTIONS)
    val root = key.role == PaneRole.LIST || key.role == PaneRole.MAIN
    return when {
        root && phone -> EntryNavIcon.MENU
        root -> EntryNavIcon.NONE
        key.role == PaneRole.EXTRA -> if (sheet || panes) EntryNavIcon.CLOSE else EntryNavIcon.BACK
        phone && key.destination == Destination.CHAT -> EntryNavIcon.MENU
        panes && key.destination == nav.topLevel -> EntryNavIcon.NONE
        else -> EntryNavIcon.BACK
    }
}

/** [navIconFor]'s button: ☰ opens the drawer; ← and ✕ are Back (§3.6). */
@Composable
fun SkeinShellState.EntryNavButton(key: SkeinKey) {
    if (canToggleList(key)) {
        val destination = nav.topLevel
        val label =
            (if (isListExpanded(destination)) "Hide " else "Show ") +
                (if (destination == Destination.CHAT) "chats" else "notes")
        IconButton(
            onClick = { toggleList(destination) },
            modifier = Modifier.size(SkeinSize.touchTarget).testTag(EntryChromeTestTags.LIST_TOGGLE),
        ) {
            Icon(painterResource(SkeinIcons.Sidebar), contentDescription = label, modifier = Modifier.size(SkeinSize.iconStandard))
        }
        return
    }
    val icon = navIconFor(key)
    val mode = LocalSkeinWindowLayout.current.navMode()
    val openDrawer = LocalSkeinDrawerOpener.current
    val (res, label) =
        when (icon) {
            EntryNavIcon.MENU -> SkeinIcons.Menu to "Open navigation"
            EntryNavIcon.BACK -> SkeinIcons.Back to "Back"
            EntryNavIcon.CLOSE -> SkeinIcons.Close to "Close"
            EntryNavIcon.NONE -> return
        }
    IconButton(
        onClick = { if (icon == EntryNavIcon.MENU) openDrawer() else navigate { back(it, mode) } },
        modifier = Modifier.testTag(EntryChromeTestTags.NAV_ICON),
    ) {
        Icon(painterResource(res), contentDescription = label, modifier = Modifier.size(SkeinSize.iconStandard))
    }
}

@Composable
fun SkeinShellState.hasEntryNavigation(key: SkeinKey): Boolean =
    navIconFor(key) != EntryNavIcon.NONE || canToggleList(key)

@Composable
private fun SkeinShellState.canToggleList(key: SkeinKey): Boolean =
    LocalSkeinWindowLayout.current.maxPanes > 1 &&
        !LocalEntryIsList.current &&
        nav.topLevel in setOf(Destination.CHAT, Destination.KNOWLEDGE) &&
        (key.role == PaneRole.LIST || key.role == PaneRole.DETAIL)

/** An entry's top bar (§3.5): [SkeinTopAppBar] with [navIconFor]'s button. */
@Composable
fun SkeinShellState.EntryTopBar(
    key: SkeinKey,
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val hasNav = hasEntryNavigation(key)
    SkeinTopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = if (hasNav) ({ EntryNavButton(key) }) else null,
        actions = actions,
    )
}

/** An icon action for [EntryTopBar] (48 dp, labelled for TalkBack). */
@Composable
fun EntryAction(
    @DrawableRes icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(SkeinSize.iconStandard))
    }
}

/**
 * A sheet surface's one-line peek (§2.5, §4.2 "collapsed into the chip row"):
 * [label] and an expand icon. The sheet scene makes the whole row the expand target.
 */
@Composable
fun SheetPeekRow(
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = SkeinSize.touchTarget).padding(horizontal = SkeinSpacing.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Icon(
            painterResource(SkeinIcons.Expand),
            contentDescription = null,
            modifier = Modifier.size(SkeinSize.iconStandard),
        )
    }
}

/** A document an entry shows, as `observeDocument` reports it. */
sealed interface EntryDocument {
    data object Loading : EntryDocument

    /** The id no longer resolves: the gone state (§3.5). */
    data object Gone : EntryDocument

    data class Present(
        val document: Document,
    ) : EntryDocument
}

/** [rawId]'s document, live; [EntryDocument.Loading] until the first read. */
@Composable
fun rememberEntryDocument(
    repository: VaultRepository,
    rawId: String,
): EntryDocument =
    produceState<EntryDocument>(EntryDocument.Loading, repository, rawId) {
        repository.observeDocument(rawId).collect { value = it?.let(EntryDocument::Present) ?: EntryDocument.Gone }
    }.value

/** The navigation kind of a stored document (the same table `navKindsOf` sanitises with). */
val DocumentKind.objectKind: ObjectKind
    get() =
        when (this) {
            DocumentKind.CHAT -> ObjectKind.CHAT
            DocumentKind.NOTE, DocumentKind.AIOUT -> ObjectKind.NOTE
            DocumentKind.ATTACHMENT -> ObjectKind.FILE
        }

/** Open by kind (IA §2 principle 2): a chat in Chat, a note or file in Knowledge (§8.3 rule 1). */
fun SkeinShellState.open(
    document: Document,
    mode: OpenMode = OpenMode.GO_TO,
) = navigate { openDocument(it, document.id, document.kind.objectKind, mode) }

/** A link inside content (§8.3 rule 2): follows, except that a chat always goes to Chat (KNOWLEDGE_UX_SPEC.md §10). */
fun SkeinShellState.follow(document: Document) =
    open(document, if (document.kind == DocumentKind.CHAT) OpenMode.GO_TO else OpenMode.FOLLOW)

/** Resolves [rawId]'s kind first; a missing document opens nothing (OBJECT_LIFECYCLE_SPEC.md §3.5). */
suspend fun SkeinShellState.followById(
    repository: VaultRepository,
    rawId: String,
) {
    repository.getDocument(rawId)?.let(::follow)
}

/**
 * §3.5's gone state, for an entry that still meets a missing id: the message and
 * one way out, which prunes every entry naming the id and shows [destination]'s
 * root — or, with none, whatever the entry was pushed from (§6.7 "return there").
 */
@Composable
fun SkeinShellState.GoneEntry(
    rawId: String,
    message: String,
    destination: Destination?,
    actionLabel: String,
    modifier: Modifier = Modifier,
) {
    SkeinEmptyState(
        headline = message,
        primaryAction =
            SkeinAction(actionLabel) {
                navigate { nav -> prune(nav, rawId).let { if (destination == null) it else switchTo(it, destination) } }
            },
        modifier = modifier.fillMaxSize().testTag(EntryChromeTestTags.GONE),
    )
}
