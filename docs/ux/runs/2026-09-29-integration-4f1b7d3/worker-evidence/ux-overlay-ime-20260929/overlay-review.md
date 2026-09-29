# AL-11 overlay + AL-19 fixed chat controls source handoff

Branch: codex/ux-overlay-ime-20260929
Base: a4ba4f455095eb3e70a673cea6fab90664649938
AL-11 commit: a7ccd0d3a (expanded overlays consume remaining safeDrawing; inspector and Connections scroll retention; bounded Connections scrolling).
AL-19 commit: f6452275d3e7534c49c50a4e4ae53f9ff9ff69cc (measured fixed chat/landing control guard; default-null standalone hinge contract).

Evidence stored in owning worktree build/agent-logs. overlay-targeted-2.log: explicit shell/chat/editor ktlintCheck + Kotlin compilation; shell EntryInsets/HingeSurface and chat Composer/Overlay/Inspector tests. Actual XML 20 pass, 0 skip/failure/error. overlay-targeted-2-junit.zip contains original XML.

overlay-full-chat-6.log: ./gradlew --max-workers=2 :feature:chat:ktlintCheck :feature:chat:check :feature:chat:compileDebugAndroidTestKotlin. Exit 0, 385 tasks. Actual XML 273 cases, 261 pass, 12 existing screenshot exclusions, 0 failure/error. New tests OverlayImeInsetTest 4, TabletopChatControlsTest 3; standalone ChatScreenTest 5, ChatInferenceErrorTest 4, ComposerWikilinkCreateRowTest 1; pre-existing ChatComposerImeInsetTest 6 pass. AndroidTest task is NO-SOURCE, not instrumentation execution. Full raw XML archive and per-suite counts preserved.

Original failures retained: targeted-1 format errors; full-chat-3 format errors; full-chat-4 missing test-only adaptive/window dependencies; full-chat-5 two fixture non-vacuity assertions (510dp keyboard top did not overlap the actual field due inner bottom padding). Full-chat-5 XML remains separate. Final regression uses keyboard top 530dp and proves real field overlap on flat plus restoration on tabletop. No failed evidence was replaced.

Guard measures natural child height independent of applied clearance, computes unshifted keyboard edge from pane/window bounds, and only changes spacing if a separating tabletop hinge intersects the fixed cluster. If the header leaves no top partition room, bounded scrolling selects the usable lower partition; first and last controls tested reachable. Both ChatScreen and landing use the helper; standalone ChatScreen hinge defaults null.

Remaining gates: combined source screenshot compare/verify, coordinator combined checks and CI; real IME/fold/one-tap reopen/hardware typing acceptance and physical Fold all HOLD. No goldens/tolerances changed. No inference/controller/draft implementation changed. No CI/emulator/adb/SSH or pushes performed. All three pinned submodules initialized; pin guard and diff whitespace pass.

Later coordinator steering: physical Fold authorization was released only for its designated data-preserving update/demo runner. This worker still has no physical access or dispatch authority and made no adb/SSH/install/test calls.

Raw popup follow-up commit: 1c7d4c4a5ad381b9da79342c93720eb72d668117, depends on hinge utility original 0b6a32d3ce77891f977dc3ddac2e75801658ef8b (locally cherry-picked only for compilation as 97f234008). Popup/filter run 2 had explicit editor/timeline ktlint + affected main/AndroidTest compilation pass; 7 editor tests pass (2 new native-graphics popup tests, 5 existing NoteTabWikilink tests), timeline test 1 failed only its state-retention assertion because fixture recreated the default persona flow. The fixture now supplies stable persona flow, like production KnowledgeList; retry pending coordinator slot. Separate failure XML preserved in popup-filters-2-junit.zip. Timeline default persona-flow recreation is pre-existing and was reported to coordinator as follow-up, not changed under this scope.

Final Timeline commit dbef3547d398a9f95033eca97e676892a59aa0e3: timeline-final-3 explicit ktlint + native-graphics suite PASS (1 case, 0 failure/error/skip). Raw XML ZIP and source-file SHA256 JSON preserved. Exact compilation included one temporary build dependency line (timeline-compile-input.patch), owned in the hinge agent's separate pending FilterBar batch. Initial atomic mkdir was denied by inference lease; no Gradle launched, lease preserved. Later granted acquire succeeded after inference release; final check completed in 4s and released lease. No further builds requested.

Cleanup: removed only the exact temporary timeline implementation(:core:designsystem) line after preserving its patch and tested-source hashes. Worker Git status is clean. Before rebuilding this branch/patch alone, integrate the hinge agent's timeline dependency commit (or apply the preserved compile-input patch); coordinator combined source must contain that dependency. Do not cherry-pick local utility duplicate 97f234008 when original 0b6a32d is already integrated.
