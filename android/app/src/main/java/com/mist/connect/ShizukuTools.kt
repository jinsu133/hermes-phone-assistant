package com.mist.connect

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

class ShizukuTools(private val context: Context) {

    private val shizukuManager = ShizukuManager(context).apply { init() }

    fun handleShizukuExec(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) {
            return "Shizuku is not authorized; authorize it on the Shizuku permission screen first"
        }
        val command = args.optString("command", "").trim()
        if (command.isEmpty()) return "command must not be empty"
        return shizukuManager.executeShell(command)
    }

    fun handleShizukuKillApp(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku is not authorized"
        val pkg = args.optString("package_name", "").trim()
        if (pkg.isEmpty()) return "package_name must not be empty"
        val cmd = "am kill $pkg"
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuFreezeApp(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku is not authorized"
        val pkg = args.optString("package_name", "").trim()
        if (pkg.isEmpty()) return "package_name must not be empty"
        val cmd = "pm disable-user --user 0 $pkg"
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuUnfreezeApp(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku is not authorized"
        val pkg = args.optString("package_name", "").trim()
        if (pkg.isEmpty()) return "package_name must not be empty"
        val cmd = "pm enable --user 0 $pkg"
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuUninstallApp(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku is not authorized"
        val pkg = args.optString("package_name", "").trim()
        if (pkg.isEmpty()) return "package_name must not be empty"
        val keepData = args.optBoolean("keep_data", false)
        val keepFlag = if (keepData) "-k" else ""
        val cmd = "pm uninstall $keepFlag --user 0 $pkg"
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuGetRunningProcesses(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku is not authorized"
        val cmd = "ps -A -o PID,USER,ARGS"
        val output = shizukuManager.executeShell(cmd)
        if (output.startsWith("Execution") || output.startsWith("Shizuku")) return output
        // simple parse
        val lines = output.lines().filter { it.isNotBlank() }.drop(1) // drop header row
        val arr = JSONArray()
        lines.forEach { line ->
            val parts = line.trim().split("\\s+".toRegex(), limit = 3)
            if (parts.size >= 3) {
                arr.put(JSONObject().apply {
                    put("pid", parts[0])
                    put("user", parts[1])
                    put("cmd", parts[2])
                })
            }
        }
        return arr.toString(2)
    }

    fun handleShizukuInstallApk(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku is not authorized"
        val path = args.optString("apk_path", "").trim()
        if (path.isEmpty()) return "apk_path must not be empty"
        val allowDowngrade = args.optBoolean("allow_downgrade", false)
        val downgradeFlag = if (allowDowngrade) "-d" else ""
        val cmd = "pm install $downgradeFlag -r \"$path\""
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuGrantPermission(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku is not authorized"
        val pkg = args.optString("package_name", "").trim()
        val perm = args.optString("permission", "").trim()
        if (pkg.isEmpty() || perm.isEmpty()) return "package_name and permission must not be empty"
        val cmd = "pm grant $pkg $perm"
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuRevokePermission(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku is not authorized"
        val pkg = args.optString("package_name", "").trim()
        val perm = args.optString("permission", "").trim()
        if (pkg.isEmpty() || perm.isEmpty()) return "package_name and permission must not be empty"
        val cmd = "pm revoke $pkg $perm"
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuListPackages(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku is not authorized"
        val enabled = args.optString("enabled", "all") // all/enabled/disabled
        val cmd = when (enabled) {
            "enabled" -> "pm list packages -e"
            "disabled" -> "pm list packages -d"
            else -> "pm list packages"
        }
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuAppInfo(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku is not authorized"
        val pkg = args.optString("package_name", "").trim()
        if (pkg.isEmpty()) return "package_name must not be empty"
        val cmd = "dumpsys package $pkg | head -80"
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuStatus(): String {
        if (!shizukuManager.isRunning()) {
            return "Shizuku is not running. Start the Shizuku app, or via ADB:\nsh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/files/start.sh"
        }
        if (!shizukuManager.isAvailable()) {
            return "Shizuku is running but not authorized. Authorize it on the Shizuku permission screen in the app."
        }
        val id = shizukuManager.executeShell("id")
        return "Shizuku ready\nid: $id"
    }
}