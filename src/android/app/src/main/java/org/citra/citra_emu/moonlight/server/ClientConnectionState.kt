package org.citra.citra_emu.moonlight.server

data class ClientConnectionSnapshot(
    val address: String?,
    val channel: String,
    val lastActivityEpochMillis: Long,
    val currentGameId: Int,
) {
    fun isActive(nowEpochMillis: Long = System.currentTimeMillis(), timeoutMillis: Long = ACTIVE_TIMEOUT_MILLIS): Boolean =
        address != null && nowEpochMillis - lastActivityEpochMillis <= timeoutMillis

    companion object {
        const val ACTIVE_TIMEOUT_MILLIS = 30_000L
    }
}

object ClientConnectionState {
    @Volatile private var latest = ClientConnectionSnapshot(
        address = null,
        channel = "none",
        lastActivityEpochMillis = 0L,
        currentGameId = 0,
    )

    fun mark(channel: String, address: String?, currentGameId: Int? = null) {
        val previous = latest
        latest = ClientConnectionSnapshot(
            address = address?.takeIf { it.isNotBlank() } ?: previous.address,
            channel = channel,
            lastActivityEpochMillis = System.currentTimeMillis(),
            currentGameId = currentGameId ?: previous.currentGameId,
        )
    }

    fun setCurrentGame(gameId: Int) {
        val previous = latest
        latest = previous.copy(
            currentGameId = gameId.coerceAtLeast(0),
            lastActivityEpochMillis = System.currentTimeMillis(),
        )
    }

    fun clear() {
        latest = ClientConnectionSnapshot(
            address = null,
            channel = "none",
            lastActivityEpochMillis = 0L,
            currentGameId = 0,
        )
    }

    fun snapshot(): ClientConnectionSnapshot = latest
}
