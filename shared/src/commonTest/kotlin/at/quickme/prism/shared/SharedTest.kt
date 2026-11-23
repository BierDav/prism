package at.quickme.prism.shared

import kotlinx.coroutines.flow.toList

/** The module's checks, for every platform's test runner: [serverUrl] serves
 *  [served] as JSON. Throws on the first failure. */
suspend fun sharedTests(serverUrl: String, served: Probe) {
    check(PrismShared.version().startsWith("prism-shared ")) { "version: ${PrismShared.version()}" }

    val probe = Probe(name = "round trip", count = 3, tags = listOf("a", "ü"), note = null)
    val back = PrismShared.roundTrip(probe)
    check(back == probe) { "round trip: $back" }
    val text = PrismShared.json.encodeToString(Probe.serializer(), probe)
    check(text == """{"name":"round trip","count":3,"tags":["a","ü"]}""") { "encoded: $text" }
    val lenient = PrismShared.json.decodeFromString(Probe.serializer(), """{"name":"x","count":1,"extra":true}""")
    check(lenient == Probe("x", 1)) { "unknown keys: $lenient" }

    val ticks = countdown(3, 1).flow.toList().map { it.count }
    check(ticks == listOf(3, 2, 1)) { "countdown: $ticks" }

    val fetched = PrismShared.fetchProbe(serverUrl)
    check(fetched == served) { "fetched: $fetched" }
}
