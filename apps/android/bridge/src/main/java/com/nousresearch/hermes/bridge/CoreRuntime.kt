package com.nousresearch.hermes.bridge

/**
 * The core a process embeds (blueprint §3.1): started once, either as the chat's core or as the `:gateway`
 * process's gateway host. [EmbeddedPython] is the one adapter; a composition root takes this port, so it can
 * run over a fake core.
 */
interface CoreRuntime {
    /** Starts the core, which reads [secrets] as its keystore, and returns its gateway. */
    suspend fun connect(paths: HermesPaths, secrets: SecretStore): CoreGateway

    /** Starts the core as this process's gateway host; its state changes reach [events] as JSON lines. */
    fun startGateway(paths: HermesPaths, secrets: SecretStore, events: FrameSink)

    /** The app's calls into its own Python half (the bridge contract, D26). */
    val bridge: AppBridge
}
