package app.skein.models

/** Android start IDs belong to a service instance; import tokens belong to application admission. */
internal class ModelImportServiceLifetime(
    private val imports: ModelImportCoordinator,
    private val stop: (Int) -> Unit,
) {
    private var latestStartId = 0
    private var activeToken: Long? = null

    /** Called on main, including completion dispatch; stale/malformed intents cannot consume work. */
    fun started(
        token: Long,
        startId: Int,
        dispatch: (() -> Unit) -> Unit,
    ) {
        latestStartId = startId
        if (imports.isRunning(token)) activeToken = token
        if (!imports.executePending(token) {
                dispatch {
                    if (activeToken == token && !imports.isRunning(token)) activeToken = null
                    stopIfFinished()
                }
            }
        ) {
            stopIfFinished()
        }
    }

    private fun stopIfFinished() {
        // Duplicate deliveries increment the service ID without starting another operation. Stop
        // using the latest delivered ID; a newly admitted request keeps its foreground execution.
        if (imports.state.value !is ModelImportState.Running) stop(latestStartId)
    }

    fun stopped() {
        activeToken?.let(imports::executionStopped)
        activeToken = null
    }
}
