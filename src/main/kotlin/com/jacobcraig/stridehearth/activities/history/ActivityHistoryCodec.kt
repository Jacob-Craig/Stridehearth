package com.jacobcraig.stridehearth.activities.history

import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper

/**
 * The only (de)serialisation of [ActivityChange]s: the jsonb in `activity_history.change` and the list of
 * [ActivityChange.Upgraded] in `import_batch_item.upgrades`. It uses the application's Jackson 3 [JsonMapper],
 * so Hibernate's own JSON format mapper never touches these columns (they are mapped as JSON-typed strings).
 */
@Component
internal class ActivityHistoryCodec(
    private val jsonMapper: JsonMapper,
) {
    private val changeType = object : TypeReference<ActivityChange>() {}
    private val upgradesType = object : TypeReference<List<ActivityChange.Upgraded>>() {}

    fun encode(change: ActivityChange): String = jsonMapper.writerFor(changeType).writeValueAsString(change)

    fun decode(json: String): ActivityChange = jsonMapper.readValue(json, changeType)

    fun encodeUpgrades(upgrades: List<ActivityChange.Upgraded>): String = jsonMapper.writerFor(upgradesType).writeValueAsString(upgrades)

    fun decodeUpgrades(json: String): List<ActivityChange.Upgraded> = jsonMapper.readValue(json, upgradesType)
}
