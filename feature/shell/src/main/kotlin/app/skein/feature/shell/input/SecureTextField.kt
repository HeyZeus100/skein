package app.skein.feature.shell.input

import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation

/**
 * A drop-in replacement for Compose Material 3's [TextField] for any input
 * that may touch vault-sensitive content — chat prompt, editor, search
 * field, persona system prompt editor, model import passphrases.
 *
 * ## Why (threat model §9)
 *
 * Skein's threat model flags third-party IMEs (keyboards) as an unavoidable
 * leak channel: whatever text passes through the keyboard's input
 * connection can, in principle, be logged, cached for suggestions, or fed
 * into a cloud-backed "personalized learning" model by the keyboard app —
 * entirely outside Skein's process boundary and its `FLAG_SECURE`/screenshot
 * defenses. §9's best available mitigation (short of forbidding third-party
 * keyboards outright, which the plan rejects as user-hostile) is to signal
 * the IME, via standard platform flags, that this field's content must not
 * be learned from, suggested from, or retained.
 *
 * ## What this sets
 *
 * - [KeyboardOptions.autoCorrectEnabled] = `false` — asks the IME not to
 *   autocorrect (and, on most keyboards, not to run its predictive-text
 *   pass over) the field's content.
 * - [InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS] is OR'd into the underlying
 *   `EditorInfo.inputType` — the standard `InputType` signal that a field's
 *   content should not be used to generate suggestions.
 * - [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] (API 26+) is OR'd into
 *   the underlying `EditorInfo.imeOptions` — the standard flag asking the
 *   IME not to update its personalized (on-device or cloud) language model
 *   from this field's content. This is the actual Android SDK constant
 *   behind what the threat model doc calls "no learning"; there is no
 *   separate `IME_FLAG_NO_LEARNING` constant.
 *
 * Both flags are applied by wrapping the [PlatformTextInputMethodRequest]
 * Compose hands to the platform, via [InterceptPlatformTextInput]. This is
 * the mechanism used because
 * [androidx.compose.foundation.text.input.PlatformImeOptions] — the public
 * `KeyboardOptions`-adjacent surface for `EditorInfo` — currently only
 * forwards `privateImeOptions` (a free-form string some keyboards read) and
 * has no field for standard `imeOptions`/`inputType` flags.
 *
 * ## Residual risk
 *
 * These are advisory flags: a well-behaved keyboard honors them, but
 * nothing stops a *malicious* keyboard from ignoring `IME_FLAG_*`/
 * `TYPE_TEXT_FLAG_*` and logging keystrokes anyway, or from reading the
 * screen via an accessibility service regardless of what the input
 * connection reports. This composable narrows the leak channel for the
 * common case (stock/well-behaved third-party keyboards); it is not a
 * substitute for the orthogonal mitigations in §9 — `FLAG_SECURE`
 * (`skein-v97`) against screen capture, and Skein's own no-logging
 * discipline (`SkeinLog`) against the app side of the boundary.
 *
 * @param value current field text.
 * @param onValueChange invoked with the new text on every edit.
 * @param modifier standard [Modifier].
 * @param keyboardType keyboard type to request; defaults to
 *   [KeyboardType.Text]. Callers with a narrower shape (e.g. a passphrase
 *   field) may pass [KeyboardType.Password] to also suppress on-screen
 *   preview of typed characters.
 * @param imeAction the action button the IME should show.
 * @param keyboardActions callback for [imeAction].
 * @param singleLine forces the field to lay out on one line and collapses
 *   [maxLines] to 1, matching [TextField]'s own `singleLine` semantics.
 * @param maxLines see [TextField].
 * @param minLines see [TextField].
 * @param label see [TextField].
 * @param placeholder see [TextField].
 * @param leadingIcon see [TextField].
 * @param trailingIcon see [TextField].
 * @param isError see [TextField].
 * @param visualTransformation see [TextField].
 * @param textStyle see [TextField]'s `textStyle`.
 * @param shape see [TextField].
 * @param colors see [TextField].
 * @param interactionSource see [TextField].
 */
@Composable
fun SecureTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    shape: Shape = TextFieldDefaults.shape,
    colors: TextFieldColors = TextFieldDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    InterceptPlatformTextInput(interceptor = SecureImeInterceptor) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            textStyle = textStyle,
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions =
                KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                ),
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            interactionSource = interactionSource,
            shape = shape,
            colors = colors,
        )
    }
}

/**
 * Intercepts the [PlatformTextInputMethodRequest] a [SecureTextField] hands
 * to the platform IME machinery and OR's the no-suggestions/no-learning
 * flags into the [EditorInfo] the real request already populated, once it
 * has built the real [InputConnection].
 *
 * Public because a second reference wrapper — [SecureBasicTextField] — also
 * needs to apply the identical hardening. Both wrappers, and no other
 * module code, are the only sites that speak to a raw Compose text-input
 * primitive; see `RawTextFieldTest` for the enforcement side.
 */
object SecureImeInterceptor : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        val secureRequest =
            PlatformTextInputMethodRequest { outAttrs ->
                val connection = request.createInputConnection(outAttrs)
                applyNoLearningFlags(outAttrs)
                connection
            }
        return nextHandler.startInputMethod(secureRequest)
    }
}

/**
 * Sets [InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS] and, on API 26+,
 * [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] on [outAttrs]. Extracted
 * as a standalone, directly testable function (see `SecureTextFieldTest`)
 * so the flag-setting logic can be asserted against a plain [EditorInfo]
 * without standing up a full Compose + platform IME round trip.
 */
internal fun applyNoLearningFlags(outAttrs: EditorInfo) {
    outAttrs.inputType = outAttrs.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
}
