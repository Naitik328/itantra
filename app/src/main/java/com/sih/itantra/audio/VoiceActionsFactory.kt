package com.sih.itantra.audio

import com.sih.itantra.ui.screen.VoiceActions

/**
 * Builds the [VoiceActions] bundle from a [VoiceViewModel].
 *
 * Kept out of the Activity so the wiring is stated once, and out of the ViewModel so the audio
 * layer carries no dependency on a specific screen's parameter object beyond this one file.
 */
object VoiceActionsFactory {

    fun from(viewModel: VoiceViewModel, onRequestPermission: () -> Unit) = VoiceActions(
        onTalkPressed = viewModel::onTalkPressed,
        onTalkReleased = viewModel::onTalkReleased,
        onHandsFreeToggled = viewModel::onHandsFreeToggled,
        onModeSelected = viewModel::onModeSelected,
        onRouteSelected = viewModel::onRouteSelected,
        onArchiveToggled = viewModel::onArchiveToggled,
        onEchoToggled = viewModel::onEchoToggled,
        onClearArchive = viewModel::onClearArchive,
        onTestAlert = viewModel::onTestAlert,
        onStopPlayback = viewModel::onStopPlayback,
        onRequestPermission = onRequestPermission,
    )
}
