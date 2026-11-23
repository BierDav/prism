// What every preview shares: the light and dark pair on the owner's phone,
// and the screens' state filled from the debug build's sample data
// (Sample.kt), so the previews show what the debug build shows by hand.

package at.quickme.prism.previews

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import at.quickme.prism.AlertsState
import at.quickme.prism.ApprovalsStatus
import at.quickme.prism.Inbox
import at.quickme.prism.InboxItem
import at.quickme.prism.MachinesState
import at.quickme.prism.QuestionsState
import at.quickme.prism.Sample
import at.quickme.prism.shared.Approval
import at.quickme.prism.ui.PrismTheme

/** The owner's phone, as Android Studio names it; the renderer draws the whole screen for it. */
const val PHONE = "id:pixel_9_pro"

/** A whole screen, light and dark. */
@Preview(name = "light", device = PHONE)
@Preview(name = "dark", device = PHONE, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
annotation class ScreenPreviews

/** A part, as tall as it is, light and dark. */
@Preview(name = "light", widthDp = 412)
@Preview(name = "dark", widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
annotation class PartPreviews

/** Prism's theme, light or dark as the preview says. */
@Composable
fun Themed(content: @Composable () -> Unit) = PrismTheme(content = content)

/** A part on the theme's surface. */
@Composable
fun Part(content: @Composable () -> Unit) = PrismTheme { Surface(color = MaterialTheme.colorScheme.surface, content = content) }

fun sampleAlerts() = AlertsState().apply {
    alerts = Sample.alerts().filter { it.isOpen }
}

fun sampleMachines() = MachinesState().apply {
    val metrics = Sample.hostMetrics()
    hosts = metrics.hosts
    time = metrics.time
}

fun sampleQuestions() = QuestionsState().apply {
    questions = Sample.questions()
    loaded = true
}

internal fun sampleApprovals(empty: Boolean = false) = ApprovalsStatus(
    pending = if (empty) emptyList() else listOf(Sample.approval(), Sample.approval(long = true)),
    history = if (empty) emptyList() else Sample.history(),
    key = "P-256 in StrongBox (Titan M)" to
        "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBHq3cN5Yv0mYVd4kR2bq8Jt1fXc8m2T0p7xZf4G9Lw6yQe1sA3uKd0R5tN8vB2cH7jM4nP9aX6wE1rT3yU5iO0k=",
    keyFailure = null,
)

/** The inbox with the sample approval and questions, [progress] open at [page]. */
@Composable
internal fun rememberSampleInbox(
    pending: List<Approval>,
    questions: QuestionsState,
    progress: Float = 0f,
    page: Int = 0,
): Inbox = remember {
    Inbox().apply {
        update(pending.map { InboxItem.Pending(it) } + questions.questions.map { InboxItem.Question(it) })
        showAt(progress, page)
    }
}
