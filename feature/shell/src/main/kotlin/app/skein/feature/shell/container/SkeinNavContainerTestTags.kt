// skein-xtov.24.6 (AL-07, UX Wave 3): stable test tags for the new
// navigation container, kept local to this package rather than added to
// `feature/shell/.../testing/ShellTestTags.kt` — that file backs the
// current NavDrawer/IconRail/command bar this bead is explicitly not
// touching yet (AL-09a/b delete them once the switch happens).
package app.skein.feature.shell.container

object SkeinNavContainerTestTags {
    /** Root of `SkeinNavigationContainer` (the `ModalNavigationDrawer`). */
    const val ROOT = "skein_nav_container"

    /** The modal drawer's own sheet — always in the tree; off-screen while closed. */
    const val DRAWER_SHEET = "skein_nav_drawer_sheet"

    /** The navigation rail sibling, present whenever the container is not the drawer. */
    const val RAIL = "skein_nav_rail"

    /** The Space switcher slot (drawer and rail), absent below two Spaces. */
    const val SPACE_SWITCHER = "skein_nav_space_switcher"
}
