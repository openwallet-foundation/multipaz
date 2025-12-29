import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import org.multipaz.testapp.App

val app = App.getInstance()

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(
        content = { app.Content() }
    )
}
