package app.skein.feature.shell.input

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation

/**
 * A `TextFieldValue`-based counterpart to [SecureTextField] for callers
 * (currently: the live-preview editor in `:feature:editor`) that need the
 * cursor/selection carried by [TextFieldValue] — the Material 3 String
 * overload [SecureTextField] wraps does not expose that. Applies the same
 * threat-model §9 IME hardening ([SecureImeInterceptor]:
 * `IME_FLAG_NO_PERSONALIZED_LEARNING` + `TYPE_TEXT_FLAG_NO_SUGGESTIONS`)
 * around a raw [BasicTextField], so this file joins [SecureTextField] on
 * `RawTextFieldTest`'s allowlist of files permitted to touch a raw Compose
 * text-input primitive directly.
 */
@Composable
fun SecureBasicTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    interactionSource: MutableInteractionSource? = null,
    cursorBrush: SolidColor = SolidColor(textStyle.color),
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit = { it() },
) {
    val effectiveInteractionSource =
        interactionSource ?: remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    InterceptPlatformTextInput(interceptor = SecureImeInterceptor) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle,
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
            visualTransformation = visualTransformation,
            onTextLayout = onTextLayout,
            interactionSource = effectiveInteractionSource,
            cursorBrush = cursorBrush,
            decorationBox = decorationBox,
        )
    }
}
