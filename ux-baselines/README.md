# ux-baselines/

Committed Roborazzi screenshot goldens for Skein's UX overhaul (`skein-xtov`).
Full policy: `docs/ux/UX_TEST_PLAN.md` §3–§4 (the rules below are a working
summary — that document wins on any conflict). The daily Mac loop that
produces these: `docs/ux/MAC_UX_LAB_PLAN.md` §4.

## Layout

```
ux-baselines/
  before/                    frozen — the spike's pre-overhaul "before" set. Never re-recorded.
  device-before/             frozen — the owner-device before pass.
  stage-h/, wave2/           frozen — review evidence from earlier stages. Not re-recorded either.
  device/<wave>/             append-only owner-reviewed device (L5) evidence.
  <module-dir>/<device>/     current goldens — what this README covers.
    <state-id>__<theme>__fs<NNN>[__<mod>].png
```

`<module-dir>` is the Gradle module path with `:` → `-` and the leading `-`
dropped (`:feature:chat` → `feature-chat`). One directory per Roborazzi-wired
module: `feature-chat`, `feature-editor`, `feature-graph`, `feature-models`,
`feature-settings`, `feature-shell`, `feature-timeline` today.

`<device>` is a key from `SkeinDevice` (`:testing-ui`), e.g. `fold-outer-524`,
`fold-inner-1007-land`, `phone`. `ls ux-baselines/*/fold-outer-524/` gives a
device-first view across every module.

## Naming grammar (UX_TEST_PLAN.md §3.5)

```
<state-id>__<theme>__fs<NNN>[__<mod>].png
```

| Axis | Values | Notes |
|---|---|---|
| `state-id` | `[a-z]+(-[a-z0-9]+)+` | the spec's state name verbatim (`chat-empty`, `shell-landing`); never contains a device, theme or font scale |
| `theme` | `light` \| `dark` | always present — unlike the pre-UT-2 spike, the default (`light`) is spelled out, not omitted |
| `NNN` | `100` \| `150` \| `200` | always present; `fs100` is the default font scale, also spelled out |
| `mod` | `ime` \| `bars` \| `rtl` \| `tabletop` \| `book` \| `kbd` | at most one; add a state-registry row for a combination. No test captures a `mod` yet |

`__` separates axes, so a state id can contain any number of `-`. Every name
comes out of `captureUx(spec, stateId)` (`:testing-ui`'s `CaptureUx.kt`) —
tests never hand-build a golden path.

## How it's wired (bead UT-2)

Each of the seven modules' `build.gradle.kts` sets:

```kotlin
roborazzi {
    outputDir.set(rootProject.layout.projectDirectory.dir("ux-baselines/feature-<module>"))
    compare {
        outputDir.set(layout.buildDirectory.dir("outputs/roborazzi"))
    }
}
```

plus, once in the root `gradle.properties`:

```properties
roborazzi.record.filePathStrategy=relativePathFromRoborazziContextOutputDirectory
roborazzi.record.resizeScale=0.5
```

`captureUx` passes a **relative** path (`<device>/<name>.png`); the
`filePathStrategy` is what resolves it against each module's `outputDir`
instead of the JVM working directory. `compare`/`verify` write their
`*_compare.png`/`*_actual.png` pairs into the module's own git-ignored
`build/outputs/roborazzi/`, never into `ux-baselines/` (belt-and-braces:
`.gitignore` also excludes `ux-baselines/**/*_compare.png` and
`ux-baselines/**/*_actual.png`, in case a future config mistake ever routes
them here). `resizeScale=0.5` keeps goldens at half the recorded resolution
(pixel-exact comparison still happens at the recorded scale).

## Recording, verifying, comparing

Use `tools/ux/shots` (bead ML-6) rather than retyping Gradle flags:

```sh
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home

tools/ux/shots smoke   [<module>] [--tests <pattern>]   # compose everything, write/compare nothing
tools/ux/shots compare [<module>] [--tests <pattern>]   # write *_compare.png / *_actual.png, never fails
tools/ux/shots verify  [<module>] [--tests <pattern>]   # fails on the first changed image (what CI runs)
tools/ux/shots record  <module> (--tests <pattern> | --all)
tools/ux/shots open    [<module>]                       # open the module's Roborazzi HTML report
tools/ux/shots watch   <module> <pattern>                # Gradle --continuous re-run loop
```

`<module>` is one of `chat editor graph models settings shell timeline`;
omit it on `smoke`/`compare`/`verify` to run every module in one pass.

**Record-scope guard.** `shots record` refuses to run unless you pass either
`--tests '<pattern>'` (the normal case — a bead names its record scope,
UX_TEST_PLAN.md §4.2) or `--all` (a full re-record: a design-token change
that legitimately moves every image, or this module's first commit under the
naming above). There is no way to record "everything" by accident.

After recording, the script prints `git status --short ux-baselines/` —
**every changed path must be inside the record scope you intended.**
Anything else changed means either the change leaked beyond its scope or a
test is non-deterministic: stop, attach the `*_compare.png` files to the
bead, and hand back rather than committing.

Manual equivalents (what the script runs, if you need to run Gradle
directly): `docs/ux/MAC_UX_LAB_PLAN.md` §4.2. Two gotchas that cost real
time if skipped:

- **Always `--no-build-cache`, and clean `build/intermediates/roborazzi`
  before a record.** The build cache restores an earlier run's images
  (including stale `*_compare.png`), so a cached record silently records
  nothing new (`shots record` does this for you).
- **Never run `clearRoborazzi*`** on a module wired this way — it deletes
  the module's *outputs*, which are now these committed goldens, not a
  scratch directory. If it happens: `git restore ux-baselines/`.

## `roborazzi.dumpUiTree` (bead ML-6 prototype)

Enable with `-Proborazzi.dumpUiTree=true` on any `record`/`compare`/`verify`
run. Tried on `:feature:models` (`ModelsScreenshotTest`):

- **In `record`** it writes `<name>.uitree.json` (a node tree — test tags,
  bounds at both raw and the recorded scale, semantics properties/actions)
  and `<name>.annotated.png` (a Set-of-Mark copy of the golden with each
  numbered node boxed) **next to the golden it just wrote**, correctly
  separated per device since the golden path already is. Genuinely useful
  for proving a geometry claim ("the composer spans the full width at
  `fold-outer-524`") from the JSON instead of eyeballing the PNG.
- **In `compare`/`verify`** the sidecar/annotated-image path is
  `<compare.outputDir>/<name>_actual.{uitree.json,annotated.png}` — flat,
  with no device subdirectory (our `filePathStrategy` is a custom explicit
  path, not one of Roborazzi's directory-encoding naming strategies, so the
  subdirectory-mirroring logic in `processOutputImageAndReport` doesn't
  apply). Since the state id alone repeats across every device, **a
  multi-device parameterised test's `compare`/`verify` sidecars overwrite
  each other**, and only the last device processed survives on disk. This
  is a real limitation, but a diagnostic-only one: it never affects which
  images are compared or the pass/fail result, only which sidecar is left
  to look at afterward. Scope `--tests` to one device's parameterisation
  when you actually want the sidecar for a specific window.
- Neither sidecar is ever committed: `record` writes them next to a golden
  (a genuine `*_compare`/`*_actual`-adjacent artifact this repo does not
  keep — do not `git add` them), and `compare`/`verify` write them into the
  git-ignored `build/` tree already.

## The before/after evidence rule for UI PRs (prompt §39, UX_TEST_PLAN.md §4.6)

Every PR that changes pixels carries, in its body under **UX evidence**:

| Item | Minimum |
|---|---|
| Screenshots | the changed states on `fold-outer-524` and `fold-inner-1007-land`, light (more if the record scope says so) |
| Before/after | the `*_compare.png` from `tools/ux/shots compare` against `main`'s goldens, or the paired `ux-baselines/before/` image for a first redesign (mapping: `docs/ux/UX_TEST_PLAN.md` §3.1 "Mapping old names") |
| Test | the name of every test added or changed, and its layer (`UX_TEST_PLAN.md` §2) |
| Explanation | what changed and why, in two to five sentences, naming the spec section |
| Visual diff | `git diff --stat ux-baselines/` — every path inside the record scope |
| P0 link | if it fixes a P0: the P0 id and its regression test (`UX_TEST_PLAN.md` §8) |

`Implemented redesigned chat.` is not evidence, with or without a passing
build. A UI PR without the **UX evidence** section is not reviewed.

## CI (bead UT-3)

`.github/workflows/ux-screenshots.yml` runs `./gradlew --no-build-cache
verifyRoborazziDebug --continue` for the seven Roborazzi-wired feature
modules, on every PR that touches `feature/**`, `app/src/**`,
`testing-ui/**`, `testing-fakes/**`, `ux-baselines/**` or the version
catalog, and on every push to `main`. It also asserts a verify never
modifies a golden (`git diff --exit-code -- ux-baselines/`) and enforces
the §4.4 size budget. On failure it writes a job summary (mismatch count
per module, worst first) and uploads `**/build/outputs/roborazzi/**` +
`**/build/reports/roborazzi/**` as the `ux-screenshot-diffs` artifact.

**Why non-blocking.** Roborazzi's native graphics come from
`org.robolectric:nativeruntime-dist-compat`, one artifact bundling
mac-aarch64/mac-x86_64/linux-x86_64/windows natives — nothing guarantees
identical rendering across hosts, and the goldens above were recorded only
on macOS arm64 (§4.2). The job runs `continue-on-error: true` from the day
it lands so the first runs on `main` can measure real Mac↔Linux parity
without blocking every PR on an unmeasured risk (`UX_TEST_PLAN.md` §4.3).

**The §4.3 measurement (bead UT-3b) and the chosen tolerance.** The first
`ubuntu-latest` run against these goldens (GitHub run `36277942699`)
differed only on `:feature:models` (8/50), `:feature:graph` (9/32) and
`:feature:shell` (5/306) — chat/editor/settings/timeline matched exactly.
Every differing pixel was anti-aliasing on graph canvas lines or a rounding
difference across a whole translucent fill (the graph legend surface, a
disabled button container, the drawer scrim), never a layout change. That
run's `compare.outputDir` was flat (see "A `compare`/`verify` bug this
found" below), so only the last device processed per state survived on
disk; measuring those (Pillow, `com.dropbox.differ.Color.distance`'s own
metric — each RGBA channel normalised `0f..1f`, Euclidean over the four
channels) gave:

| Module | Image | Max distance | p99.9 |
|---|---|---|---|
| `:feature:models` | `models__{light,dark}__fs100` (`fold-inner-1007-land`) | 0.01176 | 0.00555 |
| `:feature:graph` | `graph__{light,dark}__fs100` (`fold-inner-1007{,-land}`) | 0.00961 | 0.00679 |
| `:feature:shell` | `shell-drawer-open__{light,dark}__fs100` (`fold-outer-524`) | 0.00961 | 0.00392 |

(Roborazzi's own library default, `SimpleImageComparator(maxDistance =
0.007f)`, is why the previously-implicit default still failed some of
these — its comparator is tighter than several of the anti-aliased pixels
above.) `:testing-ui`'s `captureUx()` (`CaptureUx.kt`) now sets
`RoborazziOptions.CompareOptions(imageComparator = SimpleImageComparator(
maxDistance = 0.02f, hShift = 0, vShift = 0))` for every module's
`compare`/`verify` — comfortably above the measured max (≈1.7×) for the
images this run could still evidence, since a stale `compare.outputDir`
means the other 16 of the 22 total mismatches from that run were
overwritten before they could be measured (below); reproved by a
perturbation test (a 20×20 patch shifted +60/255 on each channel in a
committed golden) failing `verifyRoborazziDebug`, then passing again once
restored. `hShift`/`vShift` stay `0`: no pixel-shift tolerance, only
colour. The same 0.02 applies on the Mac too (`captureUx` is the one
shared call site for all seven modules) — it does not loosen real-change
detection there, since the Mac's own record vs. verify distance is 0.

**A `compare`/`verify` bug this found and fixed.** Every module's
`roborazzi { compare { outputDir } }` pointed at one flat directory
(`build/outputs/roborazzi`, no device subfolder). Roborazzi only
reconstructs a golden's device subdirectory under a *built-in*
directory-encoding naming strategy; ours is the explicit relative path
`filePathStrategy=relativePathFromRoborazziContextOutputDirectory` needs
(bead UT-2), so that mirroring never applied — the same mechanism the
`roborazzi.dumpUiTree` note above already flagged for its sidecar files,
but it turns out to flatten the main `_actual.png`/`_compare.png` outputs
the same way. A parameterised test's five devices for one state+theme
wrote the same flat filename, so only the last device processed survived
on disk (this is why the first `ubuntu-latest` run's `ux-screenshot-diffs`
artifact held far fewer images than its reported mismatch counts). The
pass/fail result itself was never affected — each device's comparison
still ran and was still counted — only which image was left to inspect
afterward. Fixed in `captureUx()`: each call's `RoborazziOptions` now also
sets `compareOptions.outputDirectoryPath` to `<the default compare
directory>/<device>`, so `compare`/`verify` write
`<device>/<state>__<theme>__fsNNN_{actual,compare}.png`, mirroring the
golden layout instead of colliding.

**The flip criterion (§4.3).** After the coordinator reads a full
`ubuntu-latest` run (all 22 previously-flattened images now measurable)
against the committed goldens under the tolerance above:

- 0 differing images, or differences that stay under the measured margin
  above → stay on `ubuntu-latest` at `maxDistance = 0.02f`.
- a real, larger difference surfaces → re-measure and either raise the
  tolerance (if still anti-aliasing/rounding) or move the job to
  `macos-latest` (if not).

Then, once the Wave 3 shell has merged (shell goldens stop churning), 10
consecutive green runs land on `main`, and no determinism bug is open
against a screenshot test: flip `continue-on-error` to `false` and record
the flip in the workflow's own comment and in `docs/TESTING.md`.

**Fetching the diff artefact** from a failed run:

```sh
gh run download <run-id> -n ux-screenshot-diffs
open ux-screenshot-diffs/feature/*/build/reports/roborazzi/index.html
```

`gh run list --workflow=ux-screenshots.yml` finds `<run-id>` if it isn't
already at hand.

## What stays frozen

`before/`, `device-before/`, `stage-h/` and `wave2/` are historical review
evidence — never re-recorded, never touched by `shots record`, and outside
every record scope by definition. A bead that thinks it needs to change one
of them has the wrong target.
