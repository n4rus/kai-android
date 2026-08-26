package com.axiom.kai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Termux/Python runtime for real code execution (next leverage step).
 * If com.termux is installed, we can dispatch RUN_COMMAND intents.
 * Otherwise we fall back to the sandbox shell (which has no python).
 */
object TermuxRunner {
    private const val TERMUX_PKG = "com.termux"
    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(TERMUX_PKG, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Try to run a python file via Termux. Returns true if dispatched. */
    fun runPython(ctx: Context, fileName: String, workDir: String): Boolean {
        if (!isInstalled(ctx)) return false
        return try {
            val intent = Intent(RUN_COMMAND_ACTION).apply {
                setClassName(TERMUX_PKG, RUN_COMMAND_SERVICE)
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/python3")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", fileName)
                putExtra("com.termux.RUN_COMMAND_WORKDIR", workDir)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            }
            ctx.startService(intent)
            true
        } catch (_: Exception) { false }
    }

    /** Quick check: run a shell command that tries python3, falls back to cat */
    fun pythonOrCat(ctx: Context, fileName: String): String {
        val workDir = java.io.File(ctx.filesDir, "workspace").absolutePath
        val pyCheck = Tools.shell(ctx, "python3 --version 2>&1 | head -1")
        return if (!pyCheck.contains("not found", true) && !pyCheck.contains("No such", true)) {
            Tools.shell(ctx, "python3 $fileName 2>&1 | head -40")
        } else if (isInstalled(ctx) && runPython(ctx, fileName, workDir)) {
            "▶ dispatched to Termux (check Termux app for output)"
        } else {
            "⚠ python3 not on device and Termux not installed. Install Termux from F-Droid for real Python, or use Kai-PC (python available there). File saved at workspace/$fileName — cat output:\n" +
                Tools.shell(ctx, "cat $fileName 2>&1 | head -40")
        }
    }
}
