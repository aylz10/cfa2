package com.github.kr328.clash.service

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.processingDir
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import java.util.*
import java.util.concurrent.TimeUnit

object ProfileProcessor {
    private val profileLock = Mutex()
    private val processLock = Mutex()

    suspend fun apply(context: Context, uuid: UUID, callback: IFetchObserver? = null) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val pending = PendingDao().queryByUUID(uuid)
                        ?: throw IllegalArgumentException("profile $uuid not found")

                    pending.enforceFieldValid()

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.pendingDir.resolve(pending.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    pending
                }

                val force = snapshot.type != Profile.Type.File
                var cb = callback

                Clash.fetchAndValid(context.processingDir, snapshot.source, force) {
                    try {
                        cb?.updateStatus(it)
                    } catch (e: Exception) {
                        cb = null

                        Log.w("Report fetch status: $e", e)
                    }
                }.await()

                profileLock.withLock {
                    if (PendingDao().queryByUUID(snapshot.uuid) == snapshot) {
                        context.importedDir.resolve(snapshot.uuid.toString())
                            .deleteRecursively()
                        context.processingDir
                            .copyRecursively(context.importedDir.resolve(snapshot.uuid.toString()))
                        val old = ImportedDao().queryByUUID(snapshot.uuid)
                        var upload: Long =0
                        var download: Long =0
                        var total: Long =0
                        var expire: Long =0
                        if(snapshot?.type==Profile.Type.Url){
                            val client = OkHttpClient()
                            val request = Request.Builder()
                                .url(snapshot?.source+"&flag=clash")
                                .header("User-Agent", "ClashforWindows/0.19.23")
                                .build()

                            client.newCall(request).execute().use { response ->

                                val userinfo=response.headers["subscription-userinfo"]
                                if (response.isSuccessful&&userinfo!=null){
                                    if (userinfo.split(";")?.size!! >0 && userinfo.split(";")?.get(0)?.split("=")?.size!! >1){
                                        upload= userinfo.split(";")?.get(0)?.split("=")
                                            ?.get(1)
                                            ?.toLong()
                                            ?: 0
                                    }
                                    if (userinfo.split(";")?.size!! >1 && userinfo.split(";")?.get(1)?.split("=")?.size!! >1){
                                        download= userinfo.split(";")?.get(1)?.split("=")
                                            ?.get(1)
                                            ?.toLong()
                                            ?: 0
                                    }
                                    if (userinfo.split(";")?.size!! >2 && userinfo.split(";")?.get(2)?.split("=")?.size!! >1){
                                        total=userinfo.split(";")?.get(2)?.split("=")
                                            ?.get(1)
                                            ?.toLong()
                                            ?: 0
                                    }
                                    if (userinfo.split(";")?.size!! >3 && userinfo.split(";")?.get(3)?.split("=")?.size!! >1){
                                        var expireStr=userinfo.split(";")?.get(3)?.split("=")
                                            ?.get(1);
                                        if (expireStr?.count()!! >0){
                                            expire =expireStr.toLong()

                                        }
                                    }
                                }
                                val new = Imported(
                                    snapshot.uuid,
                                    snapshot.name,
                                    snapshot.type,
                                    snapshot.source,
                                    snapshot.interval,
                                    upload,
                                    download,
                                    total,
                                    expire,
                                    old?.createdAt ?: System.currentTimeMillis()
                                )
                                if (old != null) {
                                    ImportedDao().update(new)
                                } else {
                                    ImportedDao().insert(new)
                                }

                                PendingDao().remove(snapshot.uuid)

                                context.pendingDir.resolve(snapshot.uuid.toString())
                                    .deleteRecursively()

                                context.sendProfileChanged(snapshot.uuid)
                            }
                        }else if (snapshot?.type==Profile.Type.File){
                            val new = Imported(
                                snapshot.uuid,
                                snapshot.name,
                                snapshot.type,
                                snapshot.source,
                                snapshot.interval,
                                upload,
                                download,
                                total,
                                expire,
                                old?.createdAt ?: System.currentTimeMillis()
                            )
                            if (old != null) {
                                ImportedDao().update(new)
                            } else {
                                ImportedDao().insert(new)
                            }

                            PendingDao().remove(snapshot.uuid)

                            context.pendingDir.resolve(snapshot.uuid.toString())
                                .deleteRecursively()

                            context.sendProfileChanged(snapshot.uuid)
                        }
//
//                        val new = Imported(
//                            snapshot.uuid,
//                            snapshot.name,
//                            snapshot.type,
//                            snapshot.source,
//                            snapshot.interval,
//                            0,
//                            0,
//                            0,
//                            0,
//                            old?.createdAt ?: System.currentTimeMillis()
//                        )
//
//                        if (old != null) {
//                            ImportedDao().update(new)
//                        } else {
//                            ImportedDao().insert(new)
//                        }
//
//                        PendingDao().remove(snapshot.uuid)
//
//                        context.pendingDir.resolve(snapshot.uuid.toString())
//                            .deleteRecursively()
//
//                        context.sendProfileChanged(snapshot.uuid)

                        }

                }
            }
        }
    }

    suspend fun update(context: Context, uuid: UUID, callback: IFetchObserver?) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val imported = ImportedDao().queryByUUID(uuid)
                        ?: throw IllegalArgumentException("profile $uuid not found")

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.importedDir.resolve(imported.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    imported
                }

                var cb = callback

                Clash.fetchAndValid(context.processingDir, snapshot.source, true) {
                    try {
                        cb?.updateStatus(it)
                    } catch (e: Exception) {
                        cb = null

                        Log.w("Report fetch status: $e", e)
                    }
                }.await()

                profileLock.withLock {
                    if (ImportedDao().exists(snapshot.uuid)) {
                        context.importedDir.resolve(snapshot.uuid.toString()).deleteRecursively()
                        context.processingDir
                            .copyRecursively(context.importedDir.resolve(snapshot.uuid.toString()))

                        context.sendProfileChanged(snapshot.uuid)
                    }
                }
            }
        }
    }

    suspend fun delete(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                ImportedDao().remove(uuid)
                PendingDao().remove(uuid)

                val pending = context.pendingDir.resolve(uuid.toString())
                val imported = context.importedDir.resolve(uuid.toString())

                pending.deleteRecursively()
                imported.deleteRecursively()

                context.sendProfileChanged(uuid)
            }
        }
    }
    suspend fun update(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                context.sendProfileChanged(uuid)
            }
        }
    }

    suspend fun release(context: Context, uuid: UUID): Boolean {
        return withContext(NonCancellable) {
            profileLock.withLock {
                PendingDao().remove(uuid)

                context.pendingDir.resolve(uuid.toString()).deleteRecursively()
            }
        }
    }

    suspend fun active(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                if (ImportedDao().exists(uuid)) {
                    val store = ServiceStore(context)

                    store.activeProfile = uuid

                    context.sendProfileChanged(uuid)
                }
            }
        }
    }

    private fun Pending.enforceFieldValid() {
        val scheme = Uri.parse(source)?.scheme?.lowercase(Locale.getDefault())

        when {
            name.isBlank() ->
                throw IllegalArgumentException("Empty name")
            source.isEmpty() && type != Profile.Type.File ->
                throw IllegalArgumentException("Invalid url")
            source.isNotEmpty() && scheme != "https" && scheme != "http" && scheme != "content" ->
                throw IllegalArgumentException("Unsupported url $source")
            interval != 0L && TimeUnit.MILLISECONDS.toMinutes(interval) < 15 ->
                throw IllegalArgumentException("Invalid interval")
        }
    }
}