# SKEIN UI/UX OVERHAUL
## Audit, Reference Research, Adaptive Foldable Redesign, MacBook UX Lab, Interaction Integrity, and Lifecycle Management

You are the lead orchestrator for a dedicated Skein UI/UX overhaul.

Skein is an offline-first Android AI workspace designed primarily for GrapheneOS and foldable Android hardware.

This workstream is specifically focused on:

- UI/UX quality
- information architecture
- navigation
- interaction design
- adaptive layouts
- foldable behavior
- single-screen phone behavior
- chat experience
- knowledge experience
- note management
- graph presentation
- context presentation
- model presentation
- visual hierarchy
- accessibility
- responsive behavior
- local MacBook UX prototyping
- screenshot-based regression testing
- UI-focused open-source research
- relevant agent skills
- interaction integrity
- object lifecycle behavior

There are separate agents handling inference/model-runtime issues.

Do not turn this workstream into an inference debugging project.

Do not broadly refactor:

- model execution
- llama.cpp
- RAG internals
- embeddings
- storage
- graph logic
- model loading
- backend architecture

unless a narrowly scoped UI integration change is truly required.

Protect existing application functionality while restructuring the presentation and interaction layer.

---

# 1. PRIMARY PRODUCT OBJECTIVE

The objective is:

> Make Skein feel dramatically simpler than the system underneath it.

Skein should feel like:

> A private local AI workspace with the interaction clarity of a polished chat application and the depth of a serious AI knowledge/workbench environment.

A new user should understand what to do within approximately five seconds.

A power user should progressively discover advanced functionality without the default interface becoming cluttered.

The interface should communicate confidence, coherence, and intentionality.

Working technical functionality should not feel unfinished because the surrounding UX is poor.

---

# 2. CORE PRODUCT PHILOSOPHY

Use these principles throughout the redesign:

1. Simple by default.
2. Powerful on demand.
3. Progressive disclosure over permanent complexity.
4. Every visible control must work.
5. Product language over implementation language.
6. One clear next action in every major state.
7. Advanced technical detail should be available without dominating normal use.
8. Local/offline operation should feel normal rather than technical.
9. Folded and unfolded states are both first-class experiences.
10. Visual changes require screenshot evidence.
11. Do not destabilize working backend functionality merely to improve appearance.
12. Avoid duplicate navigation systems.
13. Avoid exposing unfinished features.
14. Preserve Skein's distinctive identity rather than cloning another application.
15. The interface should remain calm even when capability density is high.

Hard rule:

> No visible production control may be nonfunctional.

If functionality is unfinished:

- implement the supported portion coherently
- hide it
- or place it behind an experimental/developer surface

Do not leave dead controls in the main interface.

---

# 3. TWO DEPTHS OF THE SAME PRODUCT

Skein should support two interaction depths without becoming two applications.

## Level 1 — Conversational

Extremely simple.

A user should be able to:

- open Skein
- start a chat
- type
- receive a response
- switch chats
- attach context when desired
- manage conversations

without understanding:

- embeddings
- graph nodes
- quantization
- context windows
- repositories
- skills
- workflows
- inference backends

This should approach the interaction cleanliness of PocketPal.

## Level 2 — Workbench / Power User

Advanced users should be able to progressively access:

- Knowledge
- files
- notes
- graph
- citations
- context inspector
- repositories
- personas
- models
- skills
- commands
- workflows
- advanced model information
- future developer tooling

without forcing those concepts into the default conversation view.

The progression should feel natural:

`Ask Skein`

→

`Attach Knowledge`

→

`Inspect Context`

→

`Use Skill`

→

`Explore Graph`

→

`Open Project / Repository`

The advanced system should feel discovered, not dumped onto the user.

---

# 4. EXTERNAL REFERENCE RESEARCH

Deploy dedicated research agents before major UI implementation.

Do not blindly copy code or visual design.

For each reference project determine:

- exact repository URL
- license
- maintenance status
- architecture
- UI framework
- relevant UX patterns
- patterns worth adapting
- components potentially reusable
- patterns that should NOT be copied
- Android relevance
- Fold relevance
- desktop/Mac development relevance
- licensing implications
- migration cost if any code is considered
- exact Skein UX problem it can help solve

Produce:

`docs/ux/REFERENCE_REPO_STUDY.md`

Every reference must answer:

> What specific problem in Skein can this project help us solve?

Do not produce generic repo summaries.

---

# 5. PRIORITY REFERENCE — POCKETPAL AI

PocketPal is an important mobile interaction benchmark.

Study the current PocketPal project and the supplied screenshots.

Focus on:

- clean chat layout
- navigation drawer
- conversation grouping
- model presentation
- reasoning/activity presentation
- message composition
- generation controls
- typography
- spacing
- touch targets
- use of negative space
- empty states
- responsive behavior
- conversation history
- model picker

PocketPal should be treated as a simplicity/quality reference for Skein's basic conversation experience.



Skein is much broader.

The lesson is:

> Each individual Skein workflow should feel as simple as it can.

Pay particular attention to PocketPal's reasoning/activity presentation.

---

# 6. PRIORITY REFERENCE — JAN

Repository:

https://github.com/janhq/jan

Jan is a high-priority reference for Skein because it sits directly at the intersection of:

- local-first AI
- privacy
- model management
- desktop AI UX
- local model discovery/import
- assistant configuration
- optional remote-provider support
- local API/workstation concepts
- extensibility

Study Jan for both UX and architecture.

Focus especially on:

- local-first AI interaction
- model selection
- model import/download UX
- friendly model naming
- local vs remote model presentation
- conversation UI
- assistant configuration
- settings organization
- model lifecycle
- provider abstraction
- local API/server UX
- MCP integration
- extensions
- privacy-first messaging
- progressive disclosure of technical settings
- desktop AI-workstation ergonomics

## Jan research questions

### 6.1 Make Local AI Feel Normal

How does Jan make a local model feel like an ordinary assistant rather than forcing the user to constantly think about inference infrastructure?

Skein should prefer:

`Qwen 2.5 3B`
`Local`

instead of exposing, by default:

- GGUF filenames
- hashes
- paths
- runtime implementation details
- long quantization identifiers

Technical details should remain available in an advanced model inspector.

### 6.2 Model Management

Study how Jan separates:

- available models
- downloaded/imported models
- active model
- provider configuration
- advanced model settings

Determine whether Skein's current Models experience exposes too much implementation complexity.

Evaluate a simpler hierarchy such as:

`Models`
- `On Device`
- `Available`
- `External Providers` [future]

### 6.3 Local + Selective External Compute

Study Jan's approach to supporting local and remote/cloud AI providers.

Do not implement external compute as part of this UI wave.

Instead ensure Skein's information architecture does not make future selective frontier-model compute difficult to add cleanly later.

Local operation should remain Skein's default identity.

### 6.4 Local API / Workstation Concept

Study Jan's local API/server approach as an architectural reference.

Do not automatically implement a Jan-style server during this UI overhaul.

Document whether similar concepts may eventually help:

- Skein desktop tooling
- CLI clients
- local agents
- repository tools
- trusted local applications
- local orchestration

Keep this separate from immediate UI implementation.

### 6.5 Desktop UX

Because Jan is a desktop-oriented AI application, study:

- sidebars
- conversation lists
- model menus
- settings
- empty states
- message width
- navigation behavior
- workspace density
- visual hierarchy

Extract patterns that may map well to Skein's unfolded Fold layout.

Do not shrink desktop Jan layouts onto the Fold outer display.

### 6.6 MacBook UX Lab Relevance

Study Jan's development workflow for ideas that could improve Skein's MacBook UI iteration loop.

Investigate:

- rapid local UX iteration
- fake/mock model state
- model-independent UI testing
- local development workflows
- desktop preview patterns

Do not turn Skein into a desktop app merely because Jan has one.

### 6.7 Extension Architecture

Study Jan's extensibility and MCP-related UX.

This is relevant to future Skein:

- skills
- tools
- workflows
- optional integrations

Do not expand this UI wave into a plugin-platform implementation.

Document how Jan accommodates extensibility without overwhelming the default experience.

## What Skein Should Extract from Jan

Potential lessons:

- make local inference feel ordinary
- separate simple model identity from technical model details
- keep model management coherent
- allow future local/cloud abstraction without redesigning the whole UI
- keep extensions secondary to primary workflows
- provide a clean product shell over technically complex infrastructure

## What Skein Should NOT Copy Blindly

Do not assume:

- desktop navigation works on mobile
- every model-management control belongs in primary UI
- Skein should expose a local API immediately
- Skein should adopt Jan's implementation stack
- desktop information density belongs on the Fold outer display

Treat Jan as a product and architecture reference, not a template.

---

# 7. PRIORITY REFERENCE — CONTINUE

Repository:

https://github.com/continuedev/continue

Continue should be treated as a major power-user/workspace reference.

Study:

- chat vs agent workflows
- contextual interaction
- coding assistant UX
- model selection
- context selection
- repository awareness
- project awareness
- command-based workflows
- keyboard-first interaction
- CLI + graphical UI coexistence
- contextual commands
- editing workflows
- advanced capability without constant clutter
- workspace organization
- power-user ergonomics

Do not make Continue a required Skein dependency.

Treat it primarily as an architectural and UX reference.

Key research question:

> How can Skein have a PocketPal-simple front door while supporting Continue-like workspace depth?

Extract patterns rather than cloning the product.

---

# 8. ANDROID ADAPTIVE APPS SAMPLES — PRIMARY FOLD REFERENCE

Repository:

https://github.com/android/adaptive-apps-samples

This is a critical reference.

Study:

- phones
- tablets
- foldables
- List-Detail layouts
- Supporting Pane layouts
- adaptive navigation
- responsive grids
- window-size behavior
- posture changes
- fold state
- pane adaptation
- desktop-sized Android windows
- split-screen behavior

Avoid crude manually hard-coded tablet breakpoints if Android adaptive APIs solve the problem more correctly.

The interface should adapt based on available space and posture.

---

# 9. ANDROID AGENT SKILLS

Repository:

https://github.com/android/skills

Inspect skills relevant to:

- Jetpack Compose
- adaptive layouts
- foldables
- navigation
- accessibility
- testing
- screenshot testing
- large-screen layouts
- keyboard input
- pointer input

Priority:

`jetpack-compose/adaptive`

Direct reference:

https://github.com/android/skills/blob/main/jetpack-compose/adaptive/SKILL.md

Evaluate each useful skill and classify it as:

- USE NOW
- USE AS REFERENCE
- DEFER
- NOT APPLICABLE

Produce:

`docs/ux/ANDROID_SKILLS_ASSESSMENT.md`

For each skill document:

- what it provides
- compatibility with Skein
- expected implementation cost
- architectural implications
- whether it should become part of agent instructions
- whether it introduces dependencies
- whether it should only be studied as reference material

Do not install or adopt skills blindly.

---

# 10. JETPACK COMPOSE SAMPLES

Repository:

https://github.com/android/compose-samples

Study:

- Compose state handling
- navigation
- chat/input interfaces
- adaptive layouts
- foldable patterns
- responsive components
- testing
- Material 3 patterns
- animations
- screen composition

Use this as an engineering reference, not a visual template.

---

# 11. NOW IN ANDROID

Repository:

https://github.com/android/nowinandroid

Study:

- production Compose architecture
- state flows
- screen state
- modularization
- component boundaries
- adaptive design
- navigation
- unidirectional data flow
- test organization
- screenshot verification architecture

Use it as a production-quality structural reference.

Do not visually imitate it.

---

# 12. ROBORAZZI

Repository:

https://github.com/takahirom/roborazzi

Examples:

https://github.com/takahirom/roborazzi-usage-examples

Strongly evaluate Roborazzi for local screenshot regression.

We want to support:

- local JVM screenshot tests
- Compose screenshot capture
- baseline images
- visual diffs
- dark mode
- light mode
- phone dimensions
- folded dimensions
- unfolded Fold dimensions
- landscape dimensions
- UI-state fixtures
- CI verification if useful

The UI should not be considered correct merely because it compiles.

Visual changes must produce visual evidence.

---

# 13. ANYTHINGLLM

Repository:

https://github.com/Mintplex-Labs/anything-llm

Study:

- workspace concepts
- RAG UX
- document context
- source selection
- knowledge organization
- relationship between conversation and documents
- workspace boundaries
- active context presentation

Primary Skein research question:

> How should Skein communicate what knowledge is currently active in a conversation?

Do not copy a desktop-heavy layout directly onto Android.

---

# 14. OPEN WEBUI

Repository:

https://github.com/open-webui/open-webui

Study:

- local model presentation
- model switching
- knowledge
- file attachments
- tools
- plugins
- settings
- conversation management
- advanced configuration
- progressive disclosure
- provider/model complexity

Primary lesson:

> How can Skein expose extensive configuration without permanently cluttering chat?

---

# 15. LOBECHAT

Repository:

https://github.com/lobehub/lobe-chat

Study:

- polished AI interaction design
- model/provider UX
- agent configuration
- tool presentation
- multimodal composer
- side panels
- advanced settings
- visual hierarchy
- progressive disclosure

Use primarily as a product/design reference.

Review licensing carefully before any reuse.

---

# 16. LIBRECHAT

Repository:

https://github.com/danny-avila/LibreChat

Study:

- multi-model interaction
- conversation navigation
- tool/agent presentation
- attachments
- advanced model settings
- retaining a familiar chat shell around complex functionality

---

# 17. ZED

Repository:

https://github.com/zed-industries/zed

Use Zed as a professional power-interface reference.

Study:

- panes
- command palette
- keyboard shortcuts
- focus handling
- workspace density
- contextual controls
- menus
- information hierarchy
- high capability density without visual chaos

Do not turn Skein into an IDE.

Extract the principle:

> High capability does not require permanent clutter.

---

# 18. CURRENT REPOSITORY UX AUDIT

Before major redesign implementation, audit the current Skein repository.

Do not start by merely restyling screens.

Understand the existing implementation first.

Produce:

`docs/ux/UX_AUDIT.md`

Inventory:

- screens
- routes
- Composables
- ViewModels
- navigation destinations
- drawers
- rails
- tabs
- menus
- dialogs
- buttons
- icon buttons
- command actions
- text fields
- model selectors
- chat controls
- note controls
- file controls
- graph controls
- persona controls
- context controls
- empty states
- loading states
- error states
- phone layouts
- Fold layouts
- keyboard states

For every visible interactive element identify:

- what it appears to do
- what it actually does
- handler/callback
- state source
- whether it works
- whether it partially works
- whether it is dead
- whether it duplicates another control
- whether terminology is understandable
- whether it belongs in v1
- whether it should move
- whether it should be removed
- whether it should be hidden

---

# 19. INTERACTION MATRIX

Produce:

`docs/ux/UX_INTERACTION_MATRIX.md`

Suggested schema:

| Surface | Control | Expected Action | Actual Action | State | Severity | Recommendation |
|---|---|---|---|---|---|---|

Allowed states:

- WORKING
- PARTIAL
- DEAD
- DUPLICATE
- CONFUSING
- FUTURE
- REMOVE

Dead controls are P0 UX defects.

---

# 20. INFORMATION ARCHITECTURE

Audit all navigation paradigms currently present.

Skein may currently contain overlapping:

- hamburger drawer
- icon rail
- sidebar
- tabs
- command bar
- timeline
- context controls
- workspace panes
- persona selector
- files/notes tabs

Determine which navigation systems should remain.

Strongly evaluate a simplified primary model:

- Chat
- Knowledge
- Graph
- Models
- Settings

Do not assume every subsystem deserves primary navigation.

Examples:

### Notes

Likely belong under:

`Knowledge`

### Files

Likely belong under:

`Knowledge`

### Repositories

Potentially belong under:

`Knowledge`

or a future:

`Workspace / Projects`

concept.

### Personas

Could be a session/chat parameter rather than a primary destination.

### Skills

Could appear contextually and/or under advanced configuration.

### Commands

Could live inside a global command palette.

Produce:

`docs/ux/INFORMATION_ARCHITECTURE.md`

Include:

- current IA
- proposed IA
- migration reasoning
- navigation diagram

---

# 21. CORE INTERACTION MODEL

Evaluate this model:

> NAVIGATE → WORK → INSPECT

On large displays:

`Navigation | Workspace | Optional Inspector`

Examples:

### Chat

`Conversations | Active Conversation | Context`

### Knowledge

`Knowledge List | Active Document | Relationships`

### Graph

`Graph | Selected Node | Related Information`

### Repository

`Files | Active Work | Context`

On narrow displays:

show one primary surface.

Secondary surfaces become:

- drawer
- bottom sheet
- modal sheet
- secondary route
- fullscreen detail
- context sheet

Never squeeze three columns onto the outer Fold screen.

---

# 22. CLOSED FOLD / SINGLE-SCREEN MODE — MANDATORY

Skein must work as a polished single-screen mobile application when the foldable device is closed.

This is a first-class product requirement.

The outer display must not look like the unfolded interface compressed into a narrow viewport.

When closed:

- one dominant workspace at a time
- no persistent multi-pane UI
- no compressed sidebar
- no orphaned icon rail
- no tiny metadata
- no unreadable labels
- no vertically stacked sidebar icons floating in the center
- no huge accidental empty areas caused by incorrect layout state
- no desktop interface squeezed into phone width
- full-width composer
- proper touch targets
- readable typography
- mobile navigation patterns

Secondary functionality should use:

- drawer
- bottom sheet
- modal sheet
- fullscreen route

Example chat concept:

```text
┌─────────────────────────────┐
│ ☰  Architecture Chat   ⋮   │
│    Qwen 2.5 3B · Local     │
├─────────────────────────────┤
│                             │
│ You                         │
│ Explain this architecture.  │
│                             │
│ Skein                       │
│ ▸ Worked for 6.2s           │
│                             │
│ The architecture...         │
│                             │
├─────────────────────────────┤
│ ＋  Ask Skein...       ↑    │
└─────────────────────────────┘
```

Do not require application restart when the phone is closed.

---

# 23. OPEN FOLD EXPERIENCE

When the Fold opens, Skein should take advantage of the additional width.

Do not merely enlarge the phone layout.

Evaluate:

`NAVIGATION | WORKSPACE | CONTEXT`

when appropriate.

Examples:

### Chat

Left:
- conversations

Center:
- active conversation

Right:
- optional context inspector

### Knowledge

Left:
- collections/documents

Center:
- selected document

Right:
- metadata/backlinks/graph/context

### Graph

Large graph canvas plus optional selected-node details.

Additional panes should only appear when they add value.

Whitespace is not inherently bad, but unused space caused by weak layout architecture is.

---

# 24. FOLD / UNFOLD LIVE TRANSITION — MANDATORY

The same running app must adapt gracefully when the device transitions between:

`closed → open`

and:

`open → closed`

Do not restart the activity unnecessarily.

Do not reset the user to Home.

Preserve where practical:

- active conversation
- draft text
- scroll position
- active model selection
- persona
- active knowledge
- attached context
- selected document
- selected graph node
- current workspace
- open task state

The UI should reflow.

The application should not reset.

---

# 25. FOLD TRANSITION ACCEPTANCE TESTS

Create explicit acceptance tests.

## Test A — Open → Closed

Start unfolded.

Open:

- active chat
- context inspector
- knowledge context

Close Fold.

Expected:

- chat becomes single-screen
- secondary panes disappear appropriately
- conversation remains active
- draft remains
- no crash
- no route reset

## Test B — Closed → Open

Start closed.

Begin conversation.

Open Fold.

Expected:

- active conversation remains
- layout expands
- optional supporting panes appear
- no duplicate chat
- no reset

## Test C — While Generating

Close/open while a response is active.

UI must adapt safely.

## Test D — Keyboard Active

Close/open with keyboard visible.

Verify composer behavior.

## Test E — Knowledge

Close/open while a document is selected.

## Test F — Graph

Close/open while a graph node is selected.

## Test G — Drawer/Sheet State

Verify temporary navigation UI resolves appropriately after posture change.

---

# 26. CHAT EXPERIENCE REDESIGN

Chat should become one of the cleanest areas in Skein.

Default chat should prominently communicate:

- conversation title
- active model
- conversation
- composer
- response state
- stop/retry when applicable
- optional activity information

Do not expose raw internal filenames prominently.

Bad:

`qwen2.5-3b-instruct-abliterated-q3-k-m-2c5f9...`

Prefer:

`Qwen 2.5 3B`

secondary label:

`Local`

Full technical model information belongs in model details.

---

# 27. ACTIVITY / REASONING EXPERIENCE

PocketPal's reasoning presentation is an important reference.

Skein should create its own version.

Do not depend on exposing raw hidden chain-of-thought.

Instead provide a collapsible user-facing activity view.

During execution:

```text
Working

✓ Searching Knowledge
✓ Retrieved 7 passages
✓ Opened 2 notes
● Generating response...
```

After completion:

```text
▸ Worked for 8.1s · 7 sources · 2 notes
```

Expand on tap.

Possible activities:

- Starting model
- Searching Knowledge
- Reading note
- Reading file
- Searching repository
- Retrieving memory
- Traversing graph
- Running skill
- Running tool
- Generating
- Completed

If a model explicitly emits a supported user-visible reasoning field intended for display, handle it separately.

Do not fabricate reasoning.

---

# 28. CONVERSATION MANAGEMENT — REQUIRED

Chats must behave like persistent user objects rather than disposable debug sessions.

Users must be able to:

- create a chat
- open a chat
- rename a chat
- delete a chat
- search chats
- navigate recent chats
- optionally archive chats if architecture supports it cleanly

Avoid repeated anonymous titles:

`Chat`
`Chat`
`Chat`

Use:

- generated titles
- user titles
- meaningful previews

Examples:

`Skein UX redesign`

`RAG architecture`

`Mycology research`

`Continue repo analysis`

---

# 29. DELETE CHAT — REQUIRED

Users must be able to delete chats.

Recommended interaction:

`⋮ → Delete chat`

Provide an appropriate confirmation:

`Delete "Skein UX redesign"?`

Brief consequence:

`This removes the conversation from Skein.`

Actions:

`Cancel`

`Delete`

Do not place destructive actions where accidental taps are likely.

Use consistent destructive-action styling.

After deletion:

- remove chat immediately from conversation history
- update UI
- move user to a sensible next state
- do not leave an orphaned tab
- do not leave a dead route
- ensure chat does not reappear after restart
- keep search/history consistent

Audit the storage consequences of deletion.

Determine whether deletion should remove:

- chat rows
- messages
- generated title
- metadata
- attachments
- embeddings created solely for the chat
- derived graph relationships
- cached retrieval state
- context bindings

Do not guess.

Document the deletion contract.

---

# 30. NOTES MANAGEMENT — REQUIRED

Users must be able to:

- create a note
- edit a note
- rename a note
- open a note
- search notes
- delete a note
- navigate between notes
- return to previous workspace without losing state

Notes should feel like first-class Knowledge objects.

---

# 31. DELETE NOTE — REQUIRED

Recommended interaction:

`⋮ → Delete note`

Provide confirmation when appropriate.

If a note participates in:

- RAG
- embeddings
- graph relationships
- citations
- backlinks
- collections
- active context
- repository/project references

audit what deletion means.

Determine whether related derived state should:

- be deleted
- be detached
- be rebuilt
- be marked missing

Document the behavior.

After deletion:

- remove it from Knowledge
- update search
- update graph where applicable
- remove stale context attachments
- avoid broken navigation
- avoid broken citation targets
- ensure deletion persists after restart

---

# 32. OTHER OBJECT LIFECYCLE AUDIT

While specifically implementing chat and note deletion, inspect whether similar lifecycle controls are required for:

- imported files
- collections
- personas
- repositories
- custom models
- skills
- saved workflows

Do not necessarily implement them all in this wave.

Document gaps.

---

# 33. EMPTY STATES

Replace implementation-oriented states such as:

`No tabs open — back to timeline`

with purposeful product states.

Example:

```text
What are you working on?

Ask Skein, search your knowledge,
or continue something recent.

[ Ask Skein... ]

New chat
New note
Open file
Search Knowledge

Recent
...
```

Every empty state should answer:

> What should I do next?

---

# 34. MODEL PRESENTATION

Do not make internal filenames the primary model identity.

Primary:

`Qwen 2.5 3B`

Secondary:

`Local`

Detailed view may show:

- exact filename
- GGUF
- quantization
- backend
- context window
- model size
- RAM usage
- performance
- hash
- advanced generation configuration

Apply progressive disclosure.

Study Jan especially for this problem.

---

# 35. CONTEXT INSPECTOR

Evaluate a dedicated context inspector.

Potential information:

### Session

- model
- persona
- mode

### Knowledge

- attached notes
- files
- repositories
- retrieved passages

### Graph

- related entities
- selected nodes

### Context

- context usage
- selected sources

### Activity

- retrieval
- tool calls
- skills

On narrow phone:

context should appear via sheet or secondary route.

On Fold:

it may become a supporting pane.

---

# 36. DESIGN SYSTEM

Produce:

`docs/ux/DESIGN_SYSTEM.md`

Define:

- typography
- type scale
- spacing
- layout grid
- corner radius
- surfaces
- borders
- elevation
- semantic colors
- accent color
- dark mode
- light mode
- destructive colors
- success state
- warning state
- disabled state
- touch target minimums
- navigation items
- composer
- buttons
- icon buttons
- sheets
- dialogs
- menus
- status indicators
- chips
- cards
- message layout
- code blocks
- citations
- activity blocks
- empty states
- errors
- loading state

Preserve some of Skein's research/terminal character.

Do not use monospaced typography everywhere.

Use monospaced text primarily for:

- commands
- code
- file paths
- model technical details
- logs
- identifiers

Use a legible UI typeface for:

- navigation
- messages
- titles
- controls
- body content

---

# 37. MACBOOK UX LAB — REQUIRED

We need to iterate on Skein UI without installing a new APK on the Fold for every small visual change.

Create or propose a rapid local development loop on the MacBook.

Do not duplicate the entire product architecture unnecessarily.

Evaluate the following layers.

## Layer A — Actual Compose Preview Environment

Preferred for components that will ship.

Create realistic previews for:

- empty chat
- populated chat
- active generation
- completed response
- activity expanded
- activity collapsed
- conversation drawer
- Knowledge
- note editor
- graph
- context inspector
- model picker
- settings
- confirmation dialog
- delete chat
- delete note
- error states
- empty states

Use fixture data.

Do not require the production inference pipeline.

Provide fake state where necessary.

## Layer B — Simulated Device Dimensions

Provide previews for:

- narrow phone
- Fold outer screen
- Fold inner portrait
- Fold inner landscape
- tablet-like size

Include:

- keyboard-visible state
- drawer-open state
- context-open state
- long conversations
- long titles
- very long model names

## Layer C — Optional Interactive Localhost Prototype

Evaluate creating:

`ux-lab/`

served on the MacBook locally, for example:

`http://localhost:5173`

This prototype may use:

- React
- Vite
- another lightweight frontend

if appropriate.

It should use fake data.

Purpose:

- rapid design iteration
- layout experiments
- responsive testing
- interaction prototyping
- navigation experiments
- stakeholder review

This is NOT production Skein.

Do not duplicate backend logic.

Do not accidentally turn Skein into a web application.

Production remains Android/Compose unless a separate architectural decision explicitly changes that.

---

# 38. COMPOSE DESKTOP EVALUATION

Evaluate whether a limited Compose Desktop / Compose Multiplatform UI preview target would materially improve development velocity.

Do not migrate the application to Compose Multiplatform solely for this purpose.

Document:

- migration cost
- reusable UI percentage
- maintenance cost
- benefit versus Compose Preview + Roborazzi
- risk

Recommend:

- ADOPT
- EXPERIMENT
- DEFER
- REJECT

---

# 39. SCREENSHOT REGRESSION — REQUIRED

Implement or propose a visual baseline system.

Prefer evaluating Roborazzi.

Example structure:

```text
ux-baselines/

  phone/
    chat-empty.png
    chat-active.png
    chat-complete.png
    drawer.png
    knowledge.png
    delete-chat.png

  fold-outer/
    chat-empty.png
    chat-active.png
    drawer.png

  fold-inner/
    chat-context.png
    knowledge-detail.png
    graph-detail.png

  fold-landscape/
    three-pane-chat.png
```

Every major UI wave should produce screenshots.

Do not accept:

`Implemented redesigned chat.`

Require:

- screenshot
- before/after
- relevant test
- explanation
- visual diff when appropriate

---

# 40. ACCESSIBILITY AUDIT

Audit:

- touch targets
- contrast
- font scaling
- screen reader labels
- content descriptions
- keyboard navigation
- focus order
- destructive confirmation
- motion sensitivity
- status communication
- selected state
- disabled state

Fold users may also use:

- physical keyboards
- touchpad/mouse
- stylus
- accessibility services

Do not design exclusively for touch.

---

# 41. KEYBOARD & POWER-USER INPUT

Especially on the unfolded Fold with a physical keyboard, evaluate:

- command palette shortcut
- new chat shortcut
- search shortcut
- close pane
- send message
- cancel generation
- navigation shortcuts
- focus composer
- open Knowledge
- contextual actions

Study Continue and Zed.

Do not make keyboard support mandatory for basic usage.

---

# 42. COMMAND PALETTE

The existing `/command` concept has potential.

Do not expose a raw command parser as the primary user experience.

Evaluate a proper command palette:

```text
Search or run a command

New chat
New note
Search Knowledge
Import file
Switch model
Open Graph
Open Settings
```

Support slash commands where useful.

But provide:

- search
- icons
- descriptions
- keyboard navigation
- contextual ranking

Power users may continue to use command syntax.

---

# 43. USER FLOW AUDIT

Test complete workflows, not just individual screens.

At minimum audit:

1. Launch application
2. Start new chat
3. Send prompt
4. View response
5. View activity
6. Stop response
7. Retry
8. Open previous chat
9. Rename chat
10. Delete chat
11. Search chats
12. Create note
13. Edit note
14. Rename note
15. Delete note
16. Search Knowledge
17. Open document
18. Attach Knowledge to chat
19. Open citation
20. Inspect context
21. Open graph node
22. Switch model
23. Change persona
24. Close Fold
25. Open Fold
26. Rotate device
27. Use physical keyboard
28. Return after app backgrounding

For every flow identify:

- unnecessary taps
- dead ends
- duplicate navigation
- confusing states
- hidden controls
- lack of feedback
- stale state
- layout bugs
- reset bugs
- terminology issues

---

# 44. VISUAL HIERARCHY AUDIT

Specifically inspect:

- what currently attracts attention first
- whether titles are clear
- whether active navigation is obvious
- whether messages dominate appropriately
- whether model information is too prominent
- whether controls compete visually
- whether white space is intentional
- whether metadata is too loud
- whether technical information overwhelms user content

Every screen should have an intentional hierarchy.

---

# 45. RESPONSIVE TEXT & CONTENT TESTING

Test:

- long model names
- long chat titles
- large font settings
- long source names
- long note titles
- long filenames
- large messages
- code blocks
- markdown tables
- citations
- deeply nested lists

Nothing should break the Fold outer screen.

---

# 46. UI PERFORMANCE

The redesign must not make the UI heavy.

Measure where appropriate:

- composition cost
- scrolling smoothness
- excessive recomposition
- giant lists
- chat rendering
- Markdown rendering
- graph rendering
- transition smoothness

Use lazy rendering where appropriate.

Avoid animations that harm usability or battery life.

---

# 47. PRIORITY LEVELS

Use:

## P0

- dead buttons
- broken navigation
- crashes
- layout unusable on Fold outer screen
- data loss
- inability to delete chats/notes
- broken composer
- reset during Fold transitions
- inaccessible important screens

## P1

- information architecture
- chat redesign
- conversation management
- note management
- adaptive Fold layouts
- context presentation
- drawer/navigation
- activity UI
- Knowledge UX
- empty states
- error states
- model presentation

## P2

- visual polish
- microinteractions
- animation
- icon consistency
- secondary typography
- advanced shortcuts
- visual refinements

Do not polish a screen heavily if its architecture is going to be replaced.

---

# 48. IMPLEMENTATION SEQUENCE

Do not perform one giant uncontrolled refactor.

## Wave 0 — Research & Audit

Deliver:

- UX_AUDIT.md
- UX_INTERACTION_MATRIX.md
- REFERENCE_REPO_STUDY.md
- ANDROID_SKILLS_ASSESSMENT.md

No large production UI rewrite yet.

## Wave 1 — Information Architecture

Deliver:

- INFORMATION_ARCHITECTURE.md
- navigation diagram
- retained/removed screens
- primary destinations
- Fold layout strategy

## Wave 2 — Design System

Implement:

- tokens
- typography
- spacing
- colors
- components
- destructive states
- dialog patterns
- responsive primitives

## Wave 3 — Adaptive Navigation Shell

Implement:

- narrow phone
- Fold outer screen
- Fold inner screen
- navigation drawer/rail
- pane behavior
- open/close transitions

Validate before proceeding.

## Wave 4 — Chat

Implement:

- chat layout
- conversation list
- composer
- activity surface
- model label
- stop/retry controls
- empty state

## Wave 5 — Conversation Lifecycle

Implement:

- generated titles
- rename
- delete
- history
- search
- confirmation
- state cleanup

## Wave 6 — Knowledge & Notes

Implement:

- Knowledge navigation
- notes
- files
- create
- edit
- rename
- delete
- search
- responsive list-detail

## Wave 7 — Context Inspector

Implement:

- active context
- sources
- notes
- graph relations
- session info

Responsive behavior:

phone:
- sheet/route

Fold:
- optional supporting pane

## Wave 8 — Graph

Improve:

- navigation
- node selection
- detail presentation
- Fold behavior
- integration with Knowledge

## Wave 9 — Models & Settings

Improve:

- friendly model identity
- advanced detail disclosure
- clear settings hierarchy
- removal of implementation-oriented clutter

Use Jan as a major reference for this wave.

## Wave 10 — Command Palette & Power UX

Implement/refine:

- command palette
- contextual commands
- keyboard support
- shortcuts
- workspace interactions

Use Continue and Zed as major references.

## Wave 11 — Screenshot Baselines & Accessibility

Complete:

- Roborazzi
- visual baselines
- Fold state screenshots
- accessibility audit
- font-scale tests
- keyboard tests
- final interaction audit

---

# 49. REQUIRED DOCUMENT DELIVERABLES

Before major implementation, deliver:

1. `docs/ux/UX_AUDIT.md`
2. `docs/ux/UX_INTERACTION_MATRIX.md`
3. `docs/ux/REFERENCE_REPO_STUDY.md`
4. `docs/ux/ANDROID_SKILLS_ASSESSMENT.md`
5. `docs/ux/INFORMATION_ARCHITECTURE.md`
6. `docs/ux/DESIGN_SYSTEM.md`
7. `docs/ux/ADAPTIVE_LAYOUT_SPEC.md`
8. `docs/ux/CHAT_UX_SPEC.md`
9. `docs/ux/KNOWLEDGE_UX_SPEC.md`
10. `docs/ux/OBJECT_LIFECYCLE_SPEC.md`
11. `docs/ux/UX_MIGRATION_PLAN.md`
12. `docs/ux/UX_TEST_PLAN.md`
13. `docs/ux/MAC_UX_LAB_PLAN.md`

---

# 50. OBJECT LIFECYCLE SPEC

`OBJECT_LIFECYCLE_SPEC.md` must document lifecycle semantics for at least:

- chats
- messages
- notes

For each:

- create
- rename
- update
- delete
- dependencies
- derived state
- confirmation behavior
- navigation behavior after deletion
- persistence guarantees

Do not allow deletion UI to exist without knowing what deletion actually means underneath.

---

# 51. ACCEPTANCE RULES

The UX overhaul is not considered complete until:

## Navigation

- every visible control works
- no duplicate confusing navigation
- major destinations are obvious

## Chat

- chat feels clean on Fold outer screen
- chat uses available Fold inner space intelligently
- activity presentation is understandable
- model identity is human-readable

## Chats

- create works
- rename works
- delete works
- history is useful
- repeated anonymous "Chat" entries are eliminated

## Notes

- create works
- edit works
- rename works
- delete works
- search works

## Fold

- closed screen is fully usable
- unfolded screen is fully usable
- open → close does not reset active state
- close → open does not reset active state
- no squeezed multi-pane interface

## Visual Quality

- screenshots exist for key states
- major visual regressions are detectable

## Accessibility

- touch targets
- contrast
- font scaling
- labels
- keyboard behavior

all meet acceptable standards.

---

# 52. RESEARCH AGENT OUTPUT FORMAT

Every research agent should return:

## Project

Repo URL

## License

## Maintenance

## Relevant Skein Problem

## Patterns Worth Adopting

## Patterns Not Worth Adopting

## Potential Reusable Code

## Android/Fold Relevance

## Mac UX-Lab Relevance

## Recommended Action

Choose:

- ADOPT
- PROTOTYPE
- STUDY ONLY
- REJECT

## Expected Benefit

## Expected Cost

Do not return generic project descriptions.

---

# 53. REFERENCE NORTH STAR

Use the following projects for different reasons:

### PocketPal
Reference for:
- mobile chat restraint
- reasoning/activity presentation
- clean conversation UX
- simple drawer/navigation

### Jan
Reference for:
- local-first AI product UX
- model management
- local vs remote abstraction
- progressive technical disclosure
- desktop AI-workstation ergonomics
- extensibility and local-server concepts

### Continue
Reference for:
- advanced AI workspace interaction
- repository/project context
- command workflows
- keyboard-first power-user UX

### AnythingLLM
Reference for:
- knowledge/context organization
- RAG/workspace mental models

### Android Adaptive Samples
Reference for:
- Fold behavior
- phone/tablet/foldable adaptation
- pane architecture
- live posture/size changes

### Android Skills
Reference for:
- agent instructions around adaptive Compose
- testing
- Android-specific implementation practices

### Open WebUI / LobeChat / LibreChat
Reference for:
- managing advanced AI complexity
- model/provider controls
- tools and settings
- progressive disclosure

### Zed
Reference for:
- high-capability workspace ergonomics
- command palette
- panes
- keyboard UX

### Now in Android / Compose Samples
Reference for:
- production-quality Compose architecture
- state handling
- modular UI
- navigation/testing

### Roborazzi
Reference for:
- visual regression discipline
- local screenshot testing
- MacBook-local UI validation

Do not clone any one project.

Synthesize the strongest ideas into a coherent Skein-native experience.

---

# 54. FINAL DESIGN NORTH STAR

The finished experience should satisfy this principle:

> Skein should feel simple until the user asks it to become powerful.

The first impression should not be:

> "This is a complicated local AI system."

It should be:

> "I know exactly what to do."

Then, as the user explores:

> "There is a surprising amount of capability here."

The Fold should strengthen that experience.

Closed:

> a beautiful, focused AI application.

Open:

> a powerful private AI workspace.

The transition between those states should feel natural and continuous.

---

# 55. FIRST ACTION

Do not begin the production redesign immediately.

First:

1. audit the repository
2. run external-reference research
3. study PocketPal, Jan, Continue, Android adaptive samples, Android skills, AnythingLLM, Open WebUI, LobeChat, LibreChat, Zed, Now in Android, Compose Samples, and Roborazzi
4. identify dead and duplicate interactions
5. map the existing navigation/state architecture
6. determine current Fold behavior
7. determine current chat/note lifecycle behavior
8. propose the new information architecture
9. propose the MacBook UX-lab workflow
10. propose screenshot-testing infrastructure
11. return the audit and migration plan for review

Only after that should major production UI implementation begin.
