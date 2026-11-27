// The model the overlay's assistant asks (Harness's Gateway.model): the best
// one of the Assistant profile (Phone › Profiles › Actions › Assistant,
// AssistantProfile), as placement ranks it right now (ModelPick). Without a
// profile, or when its models can't be read, the published assistant_model.
//
// Placement comes from the phone's replica, which needs no network; the pick
// is kept a minute, and the overlay refreshes it while the owner speaks
// (AssistantSession.warm), so a turn rarely waits for it.

package at.quickme.prism.assistant

import android.content.Context
import android.os.SystemClock
import android.util.Log
import at.quickme.prism.AssistantProfile
import at.quickme.prism.PrismApi
import at.quickme.prism.Settings
import at.quickme.prism.assistant.core.ModelPick
import at.quickme.prism.assistant.core.ProfileModel
import at.quickme.prism.shared.Placement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

object AssistantModel {
    private const val TAG = "PrismAssistant"
    private const val FRESH_MS = 60_000L
    /** How long a turn waits for placement before it takes the last pick or assistant_model. */
    private const val READ_MS = 2_000L

    private class Picked(val profile: String, val model: String, val at: Long)

    @Volatile
    private var last: Picked? = null

    /** The model for a turn now. */
    suspend fun forTurn(c: Context): String {
        val profile = AssistantProfile.get(c) ?: return Settings.assistantModel(c)
        last?.takeIf { it.profile == profile && SystemClock.elapsedRealtime() - it.at < FRESH_MS }?.let { return it.model }
        return refresh(c, profile)
    }

    /** Picks again from placement (the overlay opened): the next turn needn't. */
    suspend fun refresh(c: Context) {
        AssistantProfile.get(c)?.let { refresh(c, it) }
    }

    private suspend fun refresh(c: Context, profile: String): String {
        val placement = try {
            withTimeoutOrNull(READ_MS) { PrismApi.call(c) { placement() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "placement for the assistant's model: ${e.javaClass.simpleName}")
            null
        }
        val stale = last?.takeIf { it.profile == profile }?.model
        val model = placement?.let { pick(it, profile) }
        if (model == null) {
            val fallback = stale ?: Settings.assistantModel(c)
            if (placement != null) Log.w(TAG, "profile $profile has no model the gateway can serve; asking $fallback")
            return fallback
        }
        if (model != stale) Log.i(TAG, "assistant model $model (profile $profile)")
        last = Picked(profile, model, SystemClock.elapsedRealtime())
        return model
    }

    internal fun pick(placement: Placement, profile: String): String? =
        ModelPick.pick(
            profile,
            placement.profiles.associate { p ->
                p.id to p.configuredEntries.map { ProfileModel(it.model, it.reasoning, it.enabled, it.state, it.isProfileReference) }
            },
        )
}
