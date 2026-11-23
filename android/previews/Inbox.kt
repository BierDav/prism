// The inbox full screen (InboxPlayer): the sample approval and the
// sample questions as its pages, fully open and part way open, as it grows
// out of the bar while the bar is pulled up.

package at.quickme.prism.previews

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import at.quickme.prism.Sample
import at.quickme.prism.Tab

/** Over the Alerts tab: the sample approval, then the two sample questions, [progress] open at [page]. */
@Composable
private fun SampleInbox(progress: Float, page: Int) {
    val pending = remember { listOf(Sample.approval()) }
    val questions = remember { sampleQuestions() }
    SampleHome(Tab.Alerts, pending = pending, questions = questions, inbox = rememberSampleInbox(pending, questions, progress, page))
}

/** The approval's page. */
@ScreenPreviews
@Composable
private fun InboxApprovalPreview() = Themed { SampleInbox(1f, 0) }

/** A question with one choice. */
@ScreenPreviews
@Composable
private fun InboxQuestionPreview() = Themed { SampleInbox(1f, 1) }

/** Two questions on one page, the second with several choices. */
@ScreenPreviews
@Composable
private fun InboxQuestionsPreview() = Themed { SampleInbox(1f, 2) }

/** Pulled up a quarter: the approval's bar grows into its page. */
@ScreenPreviews
@Composable
private fun InboxQuarterOpenPreview() = Themed { SampleInbox(0.25f, 0) }

/** Pulled up over half way on a question: the page fades in, its bottom cropped. */
@ScreenPreviews
@Composable
private fun InboxHalfOpenPreview() = Themed { SampleInbox(0.6f, 1) }
