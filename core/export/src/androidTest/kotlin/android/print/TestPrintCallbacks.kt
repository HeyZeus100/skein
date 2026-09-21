// E2.I11 (bd skein-80m): `PrintDocumentAdapter.LayoutResultCallback` and
// `WriteResultCallback` (`android/print/PrintDocumentAdapter.java` in AOSP)
// both have a package-private no-arg constructor, so only a class inside
// `android.print` itself can subclass them directly — the framework hands
// callback *instances* to `onLayout`/`onWrite`, it never expects app code
// to construct one. This project has no mocking framework dependency
// (Mockito/MockK), so `MarkdownPrintAdapterTest` needs a real subclass to
// drive `MarkdownPrintAdapter` directly, per the plan's acceptance
// criterion ("Instrumented test drives the adapter directly"). Living in
// this package (even though the file physically sits under this module's
// `androidTest` source set, not inside the Android platform) is exactly
// what makes that subclassing legal — Java/Kotlin access control is
// purely package-name-based, not artifact- or signature-based.

package android.print

/** Test-only: exposes a public constructor for [PrintDocumentAdapter.LayoutResultCallback]. */
public abstract class TestLayoutResultCallback : PrintDocumentAdapter.LayoutResultCallback()

/** Test-only: exposes a public constructor for [PrintDocumentAdapter.WriteResultCallback]. */
public abstract class TestWriteResultCallback : PrintDocumentAdapter.WriteResultCallback()
