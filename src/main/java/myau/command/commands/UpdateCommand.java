package myau.command.commands;

import myau.Myau;
import myau.command.Command;
import myau.module.ModuleDocs;
import myau.update.UpdateManifest;
import myau.update.Updater;
import myau.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * .update                 status
 * .update check           check GitHub now
 * .update download        download the new version (installs when the game closes)
 * .update cancel          do not install the downloaded version this time
 * .update mode auto|notify|off
 */
public class UpdateCommand extends Command {

    public UpdateCommand() {
        super(new ArrayList<String>(Arrays.asList("update", "updater", "upd")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        Updater u = Updater.get();
        if (u == null) {
            say("更新功能沒有啟動（開發版？）", "The updater is not running (development build?)");
            return;
        }
        String sub = args.size() < 2 ? "status" : args.get(1).toLowerCase(Locale.ROOT);
        if (sub.equals("check") || sub.equals("c")) {
            say("正在檢查更新…", "Checking for updates...");
            u.checkAsync(true);
        } else if (sub.equals("download") || sub.equals("dl") || sub.equals("get")) {
            if (u.getState() == Updater.State.READY) {
                say("已經下載好了，關閉遊戲後會安裝。", "Already downloaded; it installs when the game closes.");
            } else if (u.getLatest() == null || u.getState() != Updater.State.AVAILABLE) {
                say("目前沒有可下載的新版，先輸入 .update check", "No new version to download; try .update check first");
            } else {
                say("開始下載 v" + u.getLatest().version + "…", "Downloading v" + u.getLatest().version + "...");
                u.downloadAsync();
            }
        } else if (sub.equals("cancel")) {
            u.cancelInstall();
            say("這次關閉遊戲時不會安裝更新。", "The update will not be installed when the game closes this time.");
        } else if (sub.equals("mode")) {
            Updater.Mode m = args.size() < 3 ? null : Updater.parseMode(args.get(2));
            if (m == null) {
                say("用法：.update mode auto（自動更新）| notify（只提醒）| off（關閉）",
                        "Usage: .update mode auto (install automatically) | notify (tell me only) | off");
                return;
            }
            u.setMode(m);
            say("更新模式：" + modeName(m), "Update mode: " + m.name().toLowerCase(Locale.ROOT));
        } else {
            status(u);
        }
    }

    private void status(Updater u) {
        UpdateManifest latest = u.getLatest();
        say("目前版本 v" + u.getCurrent() + "，最新版本 " + (latest == null ? "未知" : "v" + latest.version)
                        + "，模式：" + modeName(u.getMode()) + "，狀態：" + stateName(u),
                "Current v" + u.getCurrent() + ", latest " + (latest == null ? "unknown" : "v" + latest.version)
                        + ", mode: " + u.getMode().name().toLowerCase(Locale.ROOT) + ", state: " + u.getState().name().toLowerCase(Locale.ROOT)
                        + (u.isInstallCancelled() ? " (install cancelled)" : ""));
        if (u.getLastError() != null && u.getState() == Updater.State.FAILED) {
            say("上次錯誤：" + u.getLastError(), "Last error: " + u.getLastError());
        }
        say("指令：.update check | download | cancel | mode auto/notify/off",
                "Commands: .update check | download | cancel | mode auto/notify/off");
    }

    private static String modeName(Updater.Mode m) {
        switch (m) {
            case AUTO:
                return "自動更新";
            case NOTIFY:
                return "只提醒";
            default:
                return "關閉";
        }
    }

    private static String stateName(Updater u) {
        switch (u.getState()) {
            case CHECKING:
                return "檢查中";
            case UP_TO_DATE:
                return "已是最新";
            case AVAILABLE:
                return "有新版";
            case DOWNLOADING:
                return "下載中";
            case READY:
                return u.isInstallCancelled() ? "已下載（本次取消安裝）" : "已下載，關閉遊戲後安裝";
            case FAILED:
                return "失敗";
            default:
                return "尚未檢查";
        }
    }

    private static void say(String zh, String en) {
        ChatUtil.sendFormatted(Myau.clientName + (ModuleDocs.isEnglish() ? en : zh));
    }
}
