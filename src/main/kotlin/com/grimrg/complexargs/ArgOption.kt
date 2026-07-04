package com.grimrg.complexargs

/**
 * One commandline entry. [text] and [id] are the shared definition (persisted to the working-dir
 * file). [selectionOrder] is per-project working state - it is NOT written to the shared file
 * (marked @Transient) but stored locally per project and re-applied on load, keyed by [id].
 *
 * [selectionOrder] is 0 when unchecked, otherwise a monotonic tick value recording when it was
 * checked; the number shown among checked entries is its 1-based rank (see [ArgCombiner.displayRank]).
 *
 * Persistence relies on Gson deserializing without calling this constructor (so the [id] default is
 * NOT applied): a definitions file with no id yields a null id, which [ArgStore] then backfills with
 * a fresh UUID. Swapping in a Gson InstanceCreator/TypeAdapter would apply the default instead and
 * mint a new id on every load, breaking the id-keyed selection mapping.
 */
data class ArgOption(
    var text: String = "",
    @Transient var selectionOrder: Int = 0,
    var id: String? = java.util.UUID.randomUUID().toString()
)
