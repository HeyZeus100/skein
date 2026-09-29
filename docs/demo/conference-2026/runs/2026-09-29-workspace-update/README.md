# Workspace update installed; rehearsal awaits normal unlock

At 16:46 UTC on 29 September 2026, the sole physical runner verified the data-preserving update to source `28354dd25bb4995930ff46a71752f43f8a0d5df3`. The app APK copied back from the Fold matches the released APK byte hash. The prior installed APK is preserved. The coordinator independently rehashed both copies.

The candidate contains the New chat repair, collapsible Chat/Knowledge lists, two independently selected workspaces with Swap, removal of the enabled crease debug line, and first-send Search Knowledge choice. See the [controls guide](../../controls.md). These features passed source and screenshot checks; their physical rehearsal is still pending.

Normal app launch succeeded. At 16:47 UTC, Android reported the system lock showing and input restricted; the active display was the outer screen. The owner was asked to unlock normally, open Skein, complete any vault unlock, and unfold. No screenshot, live question, or new instrumentation run began after this update.

The initial host summary stopped on a missing package-metadata field after installation succeeded. That failure is preserved. A separate review of the saved records verified unchanged first-install time, data-directory paths and CE/DE inode identifiers. UID was not available in that scoped output and is not claimed. There was no reinstall, app-data clear or security-setting change.

The exact release passed independent artifact review: 5,130 CI test passes with 88 conditional skips, 552 unchanged remote screenshots, and 281 ordinary emulator cases with zero skips or failures. Two unsigned release APKs and two native libraries were compared byte for byte. These checks do not prove physical UI behavior, answer quality or generated stopping.

[Sanitized installation record](status.json) retains the exact artifact identities and hashes of the local execution records. Raw device metadata and any owner-content screenshots remain local. The original [native replay and timed-out answer attempt](../2026-09-29-physical/README.md) are unchanged. Retrieval quality, full hybrid eligibility, physical rehearsal, live answers/citations and formal Fold acceptance remain open.
