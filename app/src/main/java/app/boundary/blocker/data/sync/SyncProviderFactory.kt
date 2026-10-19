package app.boundary.blocker.data.sync

import android.content.Context

fun createSyncProvider(context: Context): SyncProvider = NoopSyncProvider
