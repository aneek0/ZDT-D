package com.android.zdtd.service.ui

import com.android.zdtd.service.ZdtdActions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

internal suspend fun ZdtdActions.awaitLoadJson(path: String): JSONObject? =
    suspendCancellableCoroutine { cont -> loadJsonData(path) { cont.resume(it) } }

internal suspend fun ZdtdActions.awaitLoadText(path: String): String? =
    suspendCancellableCoroutine { cont -> loadText(path) { cont.resume(it) } }

internal suspend fun ZdtdActions.awaitSaveJson(path: String, obj: JSONObject): Boolean =
    suspendCancellableCoroutine { cont -> saveJsonData(path, obj) { cont.resume(it) } }

internal suspend fun ZdtdActions.awaitSaveText(path: String, content: String): Boolean =
    suspendCancellableCoroutine { cont -> saveText(path, content) { cont.resume(it) } }
