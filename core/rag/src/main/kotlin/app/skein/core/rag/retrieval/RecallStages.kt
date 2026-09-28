package app.skein.core.rag.retrieval

/**
 * Production recall-stage switches for measured ablations. Defaults preserve
 * the app pipeline. Disabling graph recall does not disable PageRank blending;
 * a lexical-only evaluation also uses RankerConfig.pprWeight = 0.
 */
public data class RecallStages(
    val lexical: Boolean = true,
    val vector: Boolean = true,
    val graph: Boolean = true,
)
