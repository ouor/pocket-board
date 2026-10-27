package com.ouor.pocketboard.core.ink.android

import android.content.Context
import android.util.Log
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.ouor.pocketboard.core.ink.HandwritingRecognizer
import com.ouor.pocketboard.core.ink.InkModelManager
import com.ouor.pocketboard.core.ink.InkSettings
import com.ouor.pocketboard.core.ink.ModelState
import com.ouor.pocketboard.core.ink.rules.InkConfig
import com.ouor.pocketboard.core.ink.segment.InkLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * ko·en 모델 관리(F19, A17, A20). 프로세스에 하나(AppContainer).
 * 시작 시 [ensureDownloaded]를 한 번 부른다: 이미 받았으면 네트워크 없이 바로 Ready.
 *
 * @param context 계약 서명(architecture §15.3)용. ML Kit 은 자체 초기화 provider 로 Context 를 얻는다.
 * @param settings 개발자 옵션의 인식 방식. 인식할 때마다 현재 값을 읽는다.
 */
class MlKitInkModelManager(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: StateFlow<InkSettings> = MutableStateFlow(InkSettings()),
) : InkModelManager {

    private val _state = MutableStateFlow<ModelState>(ModelState.Checking)
    override val state: StateFlow<ModelState> = _state.asStateFlow()

    private var job: Job? = null

    /** 초기화 실패(비GMS 등, ink.md 함정 #9)면 null → Unavailable. */
    private val models: Map<InkLanguage, DigitalInkRecognitionModel>? = guard("init") {
        InkLanguage.entries.associateWith { lang ->
            DigitalInkRecognitionModel.builder(
                checkNotNull(DigitalInkRecognitionModelIdentifier.fromLanguageTag(lang.tag)) { "no model ${lang.tag}" }
            ).build()
        }
    }
    private val remote: RemoteModelManager? = guard("init") { RemoteModelManager.getInstance() }

    init {
        if (models == null || remote == null) _state.value = ModelState.Unavailable
    }

    override fun ensureDownloaded() {
        if (job?.isActive == true || _state.value == ModelState.Unavailable || _state.value == ModelState.Ready) return
        job = scope.launch { download() }
    }

    override suspend fun redownload() {
        val models = models ?: return
        val remote = remote ?: return
        job?.cancel()
        job?.join()
        _state.value = ModelState.Checking
        for (model in models.values) {
            try { remote.deleteDownloadedModel(model).await() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "delete", e) }
        }
        job = scope.launch { download() }
        job?.join()
    }

    override fun openRecognizer(): HandwritingRecognizer =
        MlKitHandwritingRecognizer(models, state, settings, InkConfig())

    private suspend fun download() {
        val models = models ?: return
        val remote = remote ?: return
        _state.value = ModelState.Checking
        val missing = models.values.filterNot { isDownloaded(remote, it) }
        val total = models.size
        var completed = total - missing.size
        for (model in missing) {
            _state.value = ModelState.Downloading(completed, total)
            val failure = downloadWithRetry(remote, model)
            if (failure != null) { _state.value = ModelState.Failed(failure); return }
            completed++
        }
        _state.value = ModelState.Ready
    }

    private suspend fun isDownloaded(remote: RemoteModelManager, model: DigitalInkRecognitionModel) =
        try { remote.isModelDownloaded(model).await() } catch (e: CancellationException) { throw e } catch (e: Exception) { false }

    /** null = 성공. 와이파이 조건 없음(PRD: 권장만). 지수 백오프 후 실패 사유. */
    private suspend fun downloadWithRetry(remote: RemoteModelManager, model: DigitalInkRecognitionModel): ModelState.Reason? {
        var reason = ModelState.Reason.OTHER
        for (attempt in 0 until RETRIES) {
            if (attempt > 0) delay(RETRY_BASE_MS shl (attempt - 1))
            try {
                remote.download(model, DownloadConditions.Builder().build()).await()
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download ${model.modelIdentifier.languageTag} attempt $attempt", e)
                reason = if ((e as? MlKitException)?.errorCode == MlKitException.NETWORK_ISSUE) ModelState.Reason.NETWORK else ModelState.Reason.OTHER
            }
        }
        return reason
    }

    private inline fun <T> guard(what: String, block: () -> T): T? =
        try { block() } catch (e: Throwable) { Log.e(TAG, what, e); null }

    private companion object {
        const val TAG = "pb.ink.models"
        const val RETRIES = 3
        const val RETRY_BASE_MS = 2_000L
    }
}
