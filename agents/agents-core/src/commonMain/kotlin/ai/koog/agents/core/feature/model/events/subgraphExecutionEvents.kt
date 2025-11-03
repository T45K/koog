package ai.koog.agents.core.feature.model.events

import ai.koog.agents.core.feature.model.AIAgentError
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
public data class SubgraphExecutionStartingEvent(
    val runId: String,
    val subgraphName: String,
    val input: JsonElement?,
    override val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
) : DefinedFeatureEvent()

@Serializable
public data class SubgraphExecutionCompletedEvent(
    val runId: String,
    val subgraphName: String,
    val input: JsonElement?,
    val output: JsonElement?,
    override val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
) : DefinedFeatureEvent()

@Serializable
public data class SubgraphExecutionFailedEvent(
    val runId: String,
    val subgraphName: String,
    val input: JsonElement?,
    val error: AIAgentError,
    override val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
) : DefinedFeatureEvent()
