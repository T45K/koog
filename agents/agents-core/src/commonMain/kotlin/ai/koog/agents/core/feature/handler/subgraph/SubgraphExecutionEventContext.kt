package ai.koog.agents.core.feature.handler.subgraph

import ai.koog.agents.core.agent.context.AIAgentContext
import ai.koog.agents.core.agent.context.AIAgentGraphContext
import ai.koog.agents.core.agent.context.AIAgentGraphContextBase
import ai.koog.agents.core.agent.entity.AIAgentSubgraph
import ai.koog.agents.core.feature.handler.AgentLifecycleEventContext
import ai.koog.agents.core.feature.handler.AgentLifecycleEventType
import kotlin.reflect.KType

/**
 * Represents the context for handling subgraph-specific events for graph strategies within the framework.
 */
public interface SubgraphExecutionEventContext : AgentLifecycleEventContext

public data class SubgraphExecutionStartingContext(
    val subgraph: AIAgentSubgraph<*, *>,
    val context: AIAgentGraphContextBase,
    val input: Any?,
    val inputType: KType,
) : SubgraphExecutionEventContext {
    override val eventType: AgentLifecycleEventType = AgentLifecycleEventType.SubgraphExecutionStarting
}

public data class SubgraphExecutionCompletedContext(
    val subgraph: AIAgentSubgraph<*, *>,
    val context: AIAgentGraphContextBase,
    val input: Any?,
    val output: Any?,
    val inputType: KType,
    val outputType: KType,
) : SubgraphExecutionEventContext {
    override val eventType: AgentLifecycleEventType = AgentLifecycleEventType.SubgraphExecutionCompleted
}

public data class SubgraphExecutionFailedContext(
    val subgraph: AIAgentSubgraph<*, *>,
    val context: AIAgentGraphContextBase,
    val input: Any?,
    val inputType: KType,
    val throwable: Throwable
) : SubgraphExecutionEventContext {
    override val eventType: AgentLifecycleEventType = AgentLifecycleEventType.SubgraphExecutionFailed
}
