package dev.netmtr.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

private val knownTelegramPackages = setOf(
    "org.telegram.messenger",
    "org.telegram.messenger.web",
    "org.thunderdog.challegram",
    "org.telegram.plus",
    "nekox.messenger",
    "com.exteragram.messenger",
    "tw.nekomimi.nekogram",
)

fun isTelegramPackage(packageName: String): Boolean {
    val name = packageName.lowercase()
    return packageName in knownTelegramPackages ||
        "telegram" in name ||
        "challegram" in name ||
        "nekogram" in name ||
        "exteragram" in name
}

fun shareReport(context: Context, plain: String, telegramHtml: String, subject: String) {
    val probe = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, plain)
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    val targets = resolveSendTargets(context, probe)
    if (targets.isEmpty()) {
        context.startActivity(Intent.createChooser(probe, "Отправить отчёт администратору"))
        return
    }
    val intents = targets.map { info ->
        val packageName = info.activityInfo.packageName
        val forTelegram = isTelegramPackage(packageName)
        Intent(Intent.ACTION_SEND).apply {
            component = ComponentName(packageName, info.activityInfo.name)
            type = if (forTelegram) "text/html" else "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            if (forTelegram) {
                putExtra(Intent.EXTRA_TEXT, telegramHtml)
                putExtra(Intent.EXTRA_HTML_TEXT, telegramHtml)
            } else {
                putExtra(Intent.EXTRA_TEXT, plain)
            }
        }
    }
    val chooser = Intent.createChooser(intents.first(), "Отправить отчёт администратору")
    if (intents.size > 1) {
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
    }
    context.startActivity(chooser)
}

@Suppress("DEPRECATION")
private fun resolveSendTargets(context: Context, intent: Intent) =
    if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        context.packageManager.queryIntentActivities(intent, 0)
    }
