package com.sih.itantra.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Enforces the footprint rule the spec calls out in bold: **one STT or one TTS model resident,
 * never both.** A quantised STT is 40–70 MB and a TTS voice 20–60 MB; letting both sit in memory
 * at once blows the budget on exactly the low-end phones this has to run on.
 *
 * Every access to an engine goes through [withStt] / [withTts]. Acquiring one side unloads the
 * other *before* it loads its own, so the peak is one model, not two. The swap is serialised by a
 * mutex, so two coroutines racing STT against TTS can't both think they won.
 */
class ModelResidency(
    val stt: SttEngine,
    val tts: TtsEngine,
) {
    enum class Resident { NONE, STT, TTS }

    private val mutex = Mutex()

    @Volatile
    var resident: Resident = Resident.NONE
        private set

    /** Run [block] with the TTS model guaranteed resident, unloading STT first if it held memory. */
    suspend fun <T> withTts(block: suspend (TtsEngine) -> T): T {
        ensureResident(Resident.TTS)
        return block(tts)
    }

    /** Run [block] with the STT model guaranteed resident, unloading TTS first if it held memory. */
    suspend fun <T> withStt(block: suspend (SttEngine) -> T): T {
        ensureResident(Resident.STT)
        return block(stt)
    }

    /** Drop whatever is resident — idle CPU/RAM, VAD-only, both heavy models asleep. */
    suspend fun releaseAll() = mutex.withLock {
        if (resident == Resident.STT) stt.unload()
        if (resident == Resident.TTS) tts.unload()
        resident = Resident.NONE
    }

    private suspend fun ensureResident(want: Resident) = mutex.withLock {
        if (resident == want) return@withLock
        when (want) {
            Resident.TTS -> {
                if (resident == Resident.STT) stt.unload()
                tts.load()
            }
            Resident.STT -> {
                if (resident == Resident.TTS) tts.unload()
                stt.load()
            }
            Resident.NONE -> Unit
        }
        resident = want
    }
}
