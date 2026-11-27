// Which model the overlay's assistant asks: the best one of the placement
// profile picked for it (Phone › Profiles › Actions › Assistant), as the
// gateway names it. The gateway routes `<account>/<model>` to that account,
// as it does for T3's agents (docs/placement.md, "account key"), and takes a
// reasoning level as a suffix: `agy/gemini-3.8-flash-high(low)`.
//
// No Android and no shared types here: AssistantModel.kt turns the phone's
// Placement into [ProfileModel]s.

package at.quickme.prism.assistant.core

/**
 * One configured entry of a profile: a model ([model] `<account>/<model>`,
 * its [reasoning] level or ""), or another profile ([profile]: [model] is
 * that profile's ID). [state] is placement's live ranking state, "" when
 * none was reported.
 */
data class ProfileModel(
    val model: String,
    val reasoning: String = "",
    val enabled: Boolean = true,
    val state: String = "",
    val profile: Boolean = false,
)

object ModelPick {
    /**
     * Reasoning levels passed on as a suffix. The gateway refuses a level the
     * model doesn't know (HTTP 400), and T3's levels (`max`, `xhigh`) aren't
     * every model's: those run at the model's default instead.
     */
    private val LEVELS = setOf("low", "medium", "high")

    /**
     * Preference by placement state, best first: `promoted` (weekly allowance
     * to spend), `ok`, `full` (fine for one more short request: it counts the
     * agents running there), `over_threshold`, `unavailable` (no host
     * reported it, which the gateway may still serve). `exhausted` and
     * `disabled` are never asked.
     */
    private val ORDER = listOf("promoted", "ok", "full", "over_threshold", "unavailable")

    private fun rank(state: String): Int = if (state.isEmpty()) 1 else ORDER.indexOf(state)

    /**
     * The gateway's model ID for profile [profileId], with its reasoning
     * suffix; null when the profile has no model the gateway could serve.
     * A profile entry stands for that profile's entries in its place, as
     * placement ranks it; a model listed twice counts at its first place.
     */
    fun pick(profileId: String, profiles: Map<String, List<ProfileModel>>): String? {
        val models = ArrayList<ProfileModel>()
        val seen = HashSet<String>()
        fun expand(id: String, path: Set<String>) {
            if (id in path) return // a cycle, which saving refuses anyway
            for (e in profiles[id].orEmpty()) {
                if (!e.enabled) continue
                if (e.profile) expand(e.model, path + id)
                else if (seen.add(e.model)) models.add(e)
            }
        }
        expand(profileId, emptySet())
        val best = models
            .filter { '/' in it.model && rank(it.state) >= 0 }
            .withIndex()
            .minWithOrNull(compareBy({ rank(it.value.state) }, { it.index }))
            ?.value ?: return null
        return gatewayId(best)
    }

    /** `account/model`, with `(level)` for a level every model takes. */
    fun gatewayId(e: ProfileModel): String =
        e.model + (e.reasoning.lowercase().takeIf { it in LEVELS }?.let { "($it)" } ?: "")
}
