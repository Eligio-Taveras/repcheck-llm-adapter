package com.repcheck.llm.adapter

import java.util.UUID

/**
 * The agentic loop never produced a schema-valid result within the LoopPolicy budget. Systemic — halt; the runner
 * raises this rather than returning an unstructured result, so nothing unconforming is ever persisted.
 */
final case class AgenticRunFailed(correlationId: UUID, iterations: Int, reason: String)
    extends Exception(s"agentic run $correlationId failed after $iterations iteration(s): $reason")
