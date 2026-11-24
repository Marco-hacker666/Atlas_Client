package atlas.nickresolver;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class NickCommand extends CommandBase {
    public static final String PREFIX = "§8[§bDenick§8] §r";

    public static volatile String typed = "/denick";
    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "list", "who", "lookup", "guess", "toggle", "set", "mode", "cloud", "channels", "status", "clear", "save");

    private final NicknameResolver core;

    public NickCommand(NicknameResolver core) {
        this.core = core;
    }

    @Override
    public String getCommandName() {
        return "denick";
    }

    @Override
    public List<String> getCommandAliases() {
        return Arrays.asList("nickres", "nr");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return typed + " <list|who|lookup|guess|toggle|set|mode|cloud|channels|status|clear|save>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        NickSettings settings = this.core.settings();
        IdentityResolver resolver = this.core.resolver();
        switch (sub) {
            case "list": {
                List<ResolvedIdentity> online = resolver.onlineIdentities();
                List<ResolvedIdentity> nicked = new ArrayList<ResolvedIdentity>();
                for (ResolvedIdentity identity : online) {
                    if (identity.isNickedWithKnownName() || identity.isNickedUnknown()) {
                        nicked.add(identity);
                    }
                }
                Collections.sort(nicked, new Comparator<ResolvedIdentity>() {
                    @Override
                    public int compare(ResolvedIdentity a, ResolvedIdentity b) {
                        return String.CASE_INSENSITIVE_ORDER.compare(nz(a.displayName), nz(b.displayName));
                    }
                });
                say(sender, "§7線上 " + online.size() + " 人，暱稱 §f" + nicked.size() + "§7 人：");
                for (ResolvedIdentity identity : nicked) {
                    String real = identity.originalName != null ? identity.originalName : "?";
                    IChatComponent line = new ChatComponentText("  §f" + identity.displayName + " §8→ "
                            + (identity.source.confident ? "§a" : "§e") + real
                            + " §8(" + IdentityLabels.source(identity.source) + ")");
                    line.setChatStyle(new ChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new ChatComponentText(IdentityLabels.details(identity)))));
                    sender.addChatMessage(line);
                }
                break;
            }
            case "who": {
                ResolvedIdentity identity = this.find(sender, args);
                if (identity != null) {
                    for (String line : IdentityLabels.details(identity).split("\n")) {
                        say(sender, line);
                    }
                }
                break;
            }
            case "lookup": {
                ResolvedIdentity identity = this.find(sender, args);
                if (identity == null) {
                    break;
                }
                if (!settings.useMojang) {
                    say(sender, "§cMojang 查詢已關閉（" + typed + " set mojang on）");
                } else if (resolver.forceLookup(identity)) {
                    say(sender, "§7已排入查詢：§f" + identity.displayName);
                } else {
                    say(sender, "§c無法查詢（非正版 UUID 或佇列已滿）");
                }
                break;
            }
            case "guess": {
                if (args.length < 2) {
                    say(sender, "§c用法：" + typed + " guess <帳號名稱>");
                    break;
                }
                ResolvedIdentity match = resolver.tryName(args[1]);
                say(sender, match != null
                        ? "§a符合！§f" + match.displayName + " §7就是 §a" + match.originalName
                        : "§7沒有離線 UUID 與 §f" + args[1] + "§7 相符");
                break;
            }
            case "toggle": {
                boolean show = !(settings.showNametag || settings.showChat);
                settings.setToggle("nametag", show);
                settings.setToggle("chat", show);
                say(sender, show ? "§a已顯示真名" : "§c已隱藏真名");
                break;
            }
            case "set": {
                if (args.length < 2) {
                    StringBuilder sb = new StringBuilder("§7設定：");
                    for (String key : settings.toggleKeys()) {
                        sb.append("\n  §f").append(key).append(" §8- §7").append(settings.label(key))
                                .append(": ").append(settings.getToggle(key) ? "§a開" : "§c關");
                    }
                    say(sender, sb.toString());
                    break;
                }
                String key = args[1].toLowerCase();
                boolean value = args.length >= 3 ? parseOnOff(args[2]) : !settings.getToggle(key);
                if (settings.setToggle(key, value)) {
                    say(sender, "§7" + settings.label(key) + "：" + (value ? "§a開" : "§c關"));
                } else {
                    say(sender, "§c沒有這個設定：" + key);
                }
                break;
            }
            case "mode": {
                NickSettings.NametagMode mode = settings.nametagMode == NickSettings.NametagMode.ABOVE
                        ? NickSettings.NametagMode.INLINE : NickSettings.NametagMode.ABOVE;
                if (args.length >= 2) {
                    mode = args[1].equalsIgnoreCase("inline") ? NickSettings.NametagMode.INLINE : NickSettings.NametagMode.ABOVE;
                }
                settings.setNametagMode(mode);
                say(sender, "§7名牌顯示方式：§f" + (mode == NickSettings.NametagMode.INLINE ? "暱稱 [真名]" : "名牌上方"));
                break;
            }
            case "cloud": {
                if (args.length >= 3 && args[1].equalsIgnoreCase("url")) {
                    settings.setCloudEndpoint(args[2], null);
                    say(sender, "§7雲端網址已設定");
                } else if (args.length >= 3 && args[1].equalsIgnoreCase("key")) {
                    settings.setCloudEndpoint(null, args[2]);
                    say(sender, "§7API 金鑰已設定");
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("flush")) {
                    this.core.cloud().flushAsync();
                    say(sender, "§7正在上傳 " + this.core.cloud().queued() + " 筆資料");
                } else {
                    CloudClient cloud = this.core.cloud();
                    say(sender, "§7雲端：" + (settings.cloudEnabled ? "§a開" : "§c關")
                            + "§7，已上傳 §f" + cloud.sentTotal() + "§7，佇列 §f" + cloud.queued()
                            + (cloud.lastError() != null ? "§7，錯誤：§c" + cloud.lastError() : ""));
                    say(sender, "§7用法：" + typed + " cloud <url 網址|key 金鑰|flush>");
                }
                break;
            }
            case "channels": {
                List<CustomPayloadInspector.ChannelStats> channels = this.core.payloads().channels();
                say(sender, "§7伺服器自訂封包頻道（" + channels.size() + "）：");
                for (CustomPayloadInspector.ChannelStats c : channels) {
                    say(sender, "  §f" + c.channel + " §8x" + c.packets.get() + " §7" + c.lastSize + "B"
                            + (c.sawUuid ? " §b含UUID" : "") + (c.hints.get() > 0 ? " §a線索 " + c.hints.get() : ""));
                }
                break;
            }
            case "status": {
                MojangApiClient mojang = this.core.mojang();
                say(sender, "§7Mojang：10 分鐘內 §f" + mojang.usedInWindow() + "/" + settings.maxRequestsPer10Min
                        + "§7，佇列 §f" + mojang.queued() + "§7，丟棄 §f" + mojang.droppedCount()
                        + (mojang.isThrottled() ? " §c（被限速）" : ""));
                say(sender, "§7快取 §f" + this.core.cache().all().size() + "§7 筆，線上 §f"
                        + resolver.online().size() + "§7 人");
                break;
            }
            case "clear":
                this.core.cache().clear();
                say(sender, "§7快取已清除");
                break;
            case "save":
                this.core.saveAsync();
                say(sender, "§7快取已儲存");
                break;
            default:
                say(sender, "§f" + typed + " list §7- 列出線上暱稱玩家\n"
                        + "§f" + typed + " who <名稱> §7- 詳細資料\n"
                        + "§f" + typed + " lookup <名稱> §7- 重新查詢 Mojang\n"
                        + "§f" + typed + " guess <帳號> §7- 測試離線 UUID 是否相符\n"
                        + "§f" + typed + " toggle §7- 顯示/隱藏真名\n"
                        + "§f" + typed + " set [設定] [on|off] §7- 切換設定\n"
                        + "§f" + typed + " mode [above|inline] §7- 名牌顯示方式\n"
                        + "§f" + typed + " cloud §7- 雲端資料庫\n"
                        + "§f" + typed + " channels | status | clear | save");
                break;
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCOMMANDS);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("who") || sub.equals("lookup")) {
                List<String> names = new ArrayList<String>();
                for (ResolvedIdentity identity : this.core.resolver().onlineIdentities()) {
                    if (identity.displayName != null) {
                        names.add(identity.displayName);
                    }
                }
                return getListOfStringsMatchingLastWord(args, names);
            }
            if (sub.equals("set")) {
                List<String> keys = new ArrayList<String>();
                for (String key : this.core.settings().toggleKeys()) {
                    keys.add(key);
                }
                return getListOfStringsMatchingLastWord(args, keys);
            }
            if (sub.equals("mode")) {
                return getListOfStringsMatchingLastWord(args, "above", "inline");
            }
            if (sub.equals("cloud")) {
                return getListOfStringsMatchingLastWord(args, "url", "key", "flush");
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return getListOfStringsMatchingLastWord(args, "on", "off");
        }
        return null;
    }

    private ResolvedIdentity find(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            say(sender, "§c請輸入玩家名稱");
            return null;
        }
        ResolvedIdentity identity = this.core.cache().find(args[1]);
        if (identity == null) {
            say(sender, "§c找不到玩家：" + args[1]);
        }
        return identity;
    }

    private static boolean parseOnOff(String s) {
        String t = s.toLowerCase();
        return t.equals("on") || t.equals("true") || t.equals("1") || t.equals("開");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static void say(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText(PREFIX + text));
    }
}
