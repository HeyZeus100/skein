package app.skein

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Single Activity for the `:app` process (spec §4.1). Shows only the app name
 * for now; the Compose shell (theme, adaptive layout, navigation) lands in
 * `E6.I1`+.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SkeinSkeleton()
        }
    }
}

@Composable
private fun SkeinSkeleton() {
    MaterialTheme {
        Surface {
            Text(text = stringResource(id = R.string.app_name))
        }
    }
}
