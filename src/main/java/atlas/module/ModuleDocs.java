package atlas.module;

import atlas.property.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the menus say about each module and its settings, in one place
 * (2026-10-04, asked for: "每個功能都要做說明", and the settings tidied).
 *
 *   DESCRIPTIONS  a short Chinese description for every registered module,
 *                 shown over the module's own (110 of 161 had none).
 *   GROUPS        for the modules with the longest lists, headings and an
 *                 order: settings are listed under the first heading whose
 *                 patterns match their key ("x*" a prefix, otherwise the
 *                 exact key, case ignored); anything unmatched goes first,
 *                 under "一般". Other modules keep their own order, with
 *                 runs of hud-/alerts-/macro- settings gathered under one
 *                 heading each.
 *   HELP          the hint line for a setting: per module where a key means
 *                 something particular, else a meaning shared by every module
 *                 that uses the same key (range, fov, move-fix, ...).
 *
 * Presentation only: no key changes, so every saved config still loads.
 * Applied once at start-up, after the settings are found (Atlas.java).
 */
public final class ModuleDocs {

    private static final Map<String, String> DESCRIPTIONS = new HashMap<String, String>();
    private static final Map<String, String[][]> GROUPS = new HashMap<String, String[][]>();
    private static final Map<String, String> HELP = new HashMap<String, String>();
    private static final Map<String, String> MODULE_HELP = new HashMap<String, String>();
    /* Key prefixes gathered under a heading in modules without a GROUPS entry. */
    private static final String[][] AUTO = {
            {"HUD 顯示", "hud", "tracker-hud"},
            {"提醒", "alerts"},
            {"巨集", "macro"},
            {"Raven 樣式", "raven"},
            {"背景", "bg-"},
    };

    private ModuleDocs() {
    }

    /* The menu's language: Atlas sets it from its Appearance page each frame. */
    private static volatile boolean english;

    /* 2026-10-22 (owner: "remove the whole Chinese interface"): the menu is English only; the Chinese
       fonts' ideographs are gone, so Chinese text could not be drawn. The tables below stay as the keys the
       English side (ModuleDocsEn) is looked up by. */
    public static boolean isEnglish() {
        return true;
    }

    public static void setEnglish(boolean value) {
        english = value;
    }

    /** A settings heading in the menu's language. */
    public static String heading(String group) {
        if (group == null || !isEnglish()) {
            return group;
        }
        String en = ModuleDocsEn.HEADINGS.get(group);
        if (en != null) {
            return en;
        }
        /* A heading without its English: never show it in Chinese (it would draw as nothing). */
        for (int i = 0; i < group.length(); i++) {
            if (group.charAt(i) >= 0x2E80) {
                return "Settings";
            }
        }
        return group;
    }

    /** Sets the description, headings and hints, and returns the settings in menu order. */
    public static List<Property<?>> apply(Module module, List<Property<?>> properties) {
        String key = module.getClass().getSimpleName();
        String description = DESCRIPTIONS.get(key);
        if (description != null) {
            module.setShownDescription(description);
        }
        String descriptionEn = ModuleDocsEn.DESCRIPTIONS.get(key);
        if (descriptionEn != null) {
            module.setShownDescriptionEn(descriptionEn);
        }
        for (Property<?> property : properties) {
            String name = norm(property.getName());
            String help = MODULE_HELP.get(key + ":" + name);
            if (help == null) {
                help = HELP.get(name);
            }
            if (help != null) {
                property.setHelp(help);
            }
            String helpEn = ModuleDocsEn.MODULE_HELP.get(key + ":" + name);
            if (helpEn == null) {
                helpEn = ModuleDocsEn.HELP.get(name);
            }
            if (helpEn != null) {
                property.setHelpEn(helpEn);
            }
        }
        /* Settings of merged modules (ModuleBundle) keep their own heading and
           come last, in order. */
        List<Property<?>> own = new ArrayList<Property<?>>();
        List<Property<?>> merged = new ArrayList<Property<?>>();
        for (Property<?> property : properties) {
            (property.isGroupFixed() ? merged : own).add(property);
        }
        String[][] spec = GROUPS.get(key);
        List<Property<?>> out = new ArrayList<Property<?>>(spec != null ? grouped(own, spec) : autoGrouped(own));
        out.addAll(merged);
        return out;
    }

    private static List<Property<?>> grouped(List<Property<?>> properties, String[][] spec) {
        List<List<Property<?>>> buckets = new ArrayList<List<Property<?>>>();
        for (int i = 0; i < spec.length; i++) {
            buckets.add(new ArrayList<Property<?>>());
        }
        List<Property<?>> rest = new ArrayList<Property<?>>();
        for (Property<?> property : properties) {
            int at = match(spec, property.getName());
            if (at < 0) {
                rest.add(property);
            } else {
                property.setGroup(spec[at][0]);
                buckets.get(at).add(property);
            }
        }
        List<Property<?>> out = new ArrayList<Property<?>>();
        for (Property<?> property : rest) {
            property.setGroup("一般");
            out.add(property);
        }
        for (List<Property<?>> bucket : buckets) {
            out.addAll(bucket);
        }
        return out;
    }

    private static List<Property<?>> autoGrouped(List<Property<?>> properties) {
        Map<String, List<Property<?>>> clusters = new LinkedHashMap<String, List<Property<?>>>();
        for (Property<?> property : properties) {
            int at = match(AUTO, property.getName(), true);
            String title = at < 0 ? null : AUTO[at][0];
            List<Property<?>> cluster = clusters.get(title);
            if (cluster == null) {
                cluster = new ArrayList<Property<?>>();
                clusters.put(title, cluster);
            }
            cluster.add(property);
        }
        /* Ungrouped first (a lone match is not a section), then each section. */
        List<Property<?>> plain = new ArrayList<Property<?>>();
        List<Property<?>> sections = new ArrayList<Property<?>>();
        for (Map.Entry<String, List<Property<?>>> cluster : clusters.entrySet()) {
            if (cluster.getKey() == null || cluster.getValue().size() < 2) {
                plain.addAll(cluster.getValue());
                continue;
            }
            for (Property<?> property : cluster.getValue()) {
                property.setGroup(cluster.getKey());
            }
            sections.addAll(cluster.getValue());
        }
        /* Keep the module's own order among the ungrouped ones. */
        List<Property<?>> ordered = new ArrayList<Property<?>>();
        for (Property<?> property : properties) {
            if (plain.contains(property)) {
                ordered.add(property);
            }
        }
        if (!sections.isEmpty()) {
            for (Property<?> property : ordered) {
                property.setGroup("一般");
            }
        }
        ordered.addAll(sections);
        return ordered;
    }

    private static int match(String[][] spec, String name) {
        return match(spec, name, false);
    }

    /** The first heading with a pattern for this key; prefixOnly treats every pattern as a prefix. */
    private static int match(String[][] spec, String name, boolean prefixOnly) {
        String key = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < spec.length; i++) {
            for (int j = 1; j < spec[i].length; j++) {
                String pattern = spec[i][j].toLowerCase(Locale.ROOT);
                if (pattern.startsWith("*")) {
                    if (key.endsWith(pattern.substring(1))) {
                        return i;
                    }
                } else if (prefixOnly || pattern.endsWith("*")) {
                    String prefix = pattern.endsWith("*") ? pattern.substring(0, pattern.length() - 1) : pattern;
                    if (key.startsWith(prefix)) {
                        return i;
                    }
                } else if (key.equals(pattern)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** A key with case, dashes, underscores and spaces taken out, for the HELP tables. */
    static String norm(String name) {
        return name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
    }

    private static void d(String module, String text) {
        DESCRIPTIONS.put(module, text);
    }

    private static void g(String module, String[]... groups) {
        GROUPS.put(module, groups);
    }

    private static String[] h(String title, String... patterns) {
        String[] out = new String[patterns.length + 1];
        out[0] = title;
        System.arraycopy(patterns, 0, out, 1, patterns.length);
        return out;
    }

    private static void help(String key, String text) {
        HELP.put(norm(key), text);
    }

    private static void help(String module, String key, String text) {
        MODULE_HELP.put(module + ":" + norm(key), text);
    }

    static {
        d("NoClickDelay", "移除兩次左鍵之間的延遲。");
        d("Parkour", "在方塊邊緣自動起跳，避免掉落。");
        d("ViewPackets", "把收發的封包名稱記錄到聊天欄，可過濾名稱。");
        d("PearlSaver", "自動瞄準並投擲終界珍珠自救。");
        d("ProjectileAimBot", "自動瞄準並投擲投射物。");
        d("SmartClicking", "依 CPS 設定智慧調整點擊。");
        d("DelayRemover", "移除點擊與跳躍的延遲。");
        d("AutoArmor", "自動穿上與更換最佳護甲。");
        d("BedWars", "BedWars 的遊戲資訊與提醒。");
        d("BedDefender", "自動放置並防守床邊防禦。");
        d("AutoBed", "自動放置床的防護方塊。");
        d("AutoPlay", "依設定自動重新進入遊戲。");
        d("BlocksESP", "高亮顯示附近的方塊。");
        d("MobESP", "高亮顯示附近的生物。");
        d("AuraAutoBlock", "攻擊時自動舉盾格擋。");
        d("ScriptModule", "載入腳本檔案並執行其鉤子。");
        d("JumpReset", "受擊時自動跳躍以減少擊退。");
        d("Tower", "快速垂直向上堆疊前進，含隧道模式。");
        d("FastUse", "加快進食與格擋的完成速度。");
        d("LegitScaffold", "低調搭橋：只在邊緣潛行並放置方塊。");
        d("BlockLadder", "預測落點並自動放置梯子。");
        d("LadderClutch", "墜落時自動放置梯子自救。");
        d("WaterClutch", "墜落時自動倒水自救（MLG）。");
        d("SkyWars", "天空之戰的遊戲輔助與資訊顯示。");
        d("SkywarsAlerts", "天空之戰的事件與對手提醒。");
        d("MurderDetector", "判斷兇手與偵探身分並提醒。");
        d("MurderMystery", "謀殺之謎遊戲的輔助工具。");
        d("AutoPot", "自動投擲藥水。");
        d("AutoSoup", "血量過低時自動喝湯。");
        d("AutoRod", "自動切換並投擲釣魚竿。");
        d("AutoWeapon", "攻擊時自動切換最佳武器。");
        d("Arrows", "在畫面邊緣指示畫面外的玩家。");
        d("ModSpoofer", "偽造客戶端品牌封包。");
        d("PingSpoof", "偽造延遲以製造封包延遲效果。");
        d("KillEffect", "擊殺時播放特效與音效。");
        d("KillMessage", "擊殺後自動發送訊息。");
        d("KillSounds", "擊殺時播放音效。");
        d("KillSults", "擊殺後自動嘲諷對手。");
        d("FallIndicator", "標記落點方便 MLG 救場。");
        d("SmartBlinker", "智慧型封包閃爍，依情況自動收發。");
        d("StaffDetector", "偵測附近的工作人員與可疑行為並發出提醒。");
        d("CheatDetector", "觀察附近玩家並在聊天室回報疑似作弊行為。");
        d("BedProximityAlert", "敵人接近你的床時發出警報。");
        d("BurstClicker", "點擊時額外送出連續點擊以提升命中率。");
        d("LegitReach", "在難以察覺的範圍內微調攻擊距離。");
        d("NoWeb", "在蜘蛛網中不再被拖慢移動速度。");
        d("Freecam", "讓視角離開身體自由飛行偵查。");
        d("AutoPlace", "準心對準方塊面時自動放置方塊。");
        d("AutoRequeue", "遊戲結束後自動重新排隊。");
        d("ArmorBreaker", "戰鬥中自動切換斧頭以破壞對手護甲。");
        d("AutoReconnect", "被踢出或斷線後自動重新連線到同一個伺服器。");
        d("AutoGG", "遊戲結束時自動發送訊息（預設是 gg）。");
        // ------------------------------------------------------------ COMBAT
        d("KillAura2", "重寫的 Silent Aura：轉頭像真手、點擊像真人，只在視角真的打得到時出手；配合 InvulnTiming 在對方無敵時間結束那一刻出手，並會自動格擋。和 Silent Aura 不能同時開。");
        d("KillAura", "自動攻擊範圍內的目標：選目標、轉頭（多種模式，可靜默）、依 CPS 出手，並可自動格擋。");
        d("AimAssist", "攻擊時把準心輕微往附近目標拉，手感接近真人微調，不會自己出手。");
        help("AimAssist", "style", "Smooth：原本的（每 tick 轉剩下角度的一部分）。Regular：每幀轉，遠快近慢。Linear：固定速度。Lock On：黏住目標。後三種參考 Slinky。");
        help("AimAssist", "speed-h", "Regular / Linear：左右速度。");
        help("AimAssist", "speed-v", "Regular / Linear：上下速度。");
        help("AimAssist", "lock-vertical", "Lock On：上下也跟。");
        help("AimAssist", "predict", "往伺服器最後給的目標位置瞄（0-1）。");
        help("AimAssist", "randomization", "瞄準點飄動的幅度（0-1）。");
        help("AimAssist", "hitbox-h", "左右：把瞄準點從中間往碰撞箱最近的邊拉（0-1）。");
        help("AimAssist", "hitbox-v", "上下：把瞄準點從眼睛高度往最近點拉（0-1）。");
        help("AimAssist", "dead-zone", "最後這幾度不轉。");
        help("AimAssist", "target-mode", "Single：鎖一個目標。Switch：每 tick 依 sort-by 選最好的。");
        help("AimAssist", "mode", "Smooth 專用。NORMAL：轉你的畫面。SILENT：畫面不動，只有伺服器收到的視角轉向敵人；點擊照這個視角打。");
        help("AimAssist", "max-offset", "SILENT：伺服器視角最多偏離你的畫面幾度；沒有目標時會慢慢轉回畫面。");
        help("AimAssist", "sort-by", "Switch：用什麼挑目標。");
        help("AimAssist", "exceed-fov", "Single：目標超出 fov 也繼續。");
        help("AimAssist", "require-click", "要按著攻擊鍵。");
        help("AimAssist", "require-mouse-move", "每幀轉的不超過你自己手動移動的量。");
        help("AimAssist", "stop-on-block-break", "挖方塊時不轉。");
        help("AimAssist", "line-of-sight", "只瞄看得到的目標。");
        help("AimAssist", "ignore-invisible", "跳過隱形的玩家。");
        d("AutoClicker", "按住左鍵時自動連點，CPS 在設定範圍內隨機，可順便格擋或挖方塊。");
        help("AutoClicker", "randomization", "Human（建議）：CPS 在範圍內慢慢漂移再回來，每一下的間隔像手一樣偏斜（很少太快、偶爾慢一點），會重複節奏、偶爾停頓，每場一開始稍快。Normal / Extra / Extra+：舊的均勻隨機。");
        d("KnockbackDelay", "把收到的擊退延後一小段時間才吃下去，讓被打飛的時機變晚。");
        help("LiquidAura", "BlockHurtTime", "Basic/Interact：自己的受傷時間高於這個值時不舉劍。剛被打的十個 tick 內不會再受傷，這時舉劍只會減速。數字越小擋越少；10 等於關閉。每次被打都會在這個值附近隨機浮動一點，像手動一樣不固定。");
        help("LiquidAura", "Rhythm-FreeRaise", "Rhythm：舉劍那一 tick 在 Grim 允許時不減速（不會連續兩次）。預設關；照 Grim 的規則做，Polar 沒測過。");
        help("KnockbackDelay", "Mode", "Steady：打架時伺服器送來的所有封包都照順序晚一段時間（keep-alive、交易封包也是），延遲慢慢加上去、慢慢退掉，伺服器看來就是網路比較慢，不會有哪一次擊退特別突出。Spike（預設）：只在部分擊退時，把擊退和後面所有封包一起壓住，畫面會定格一下，你自己的移動照常送出。Air：只在空中被打時壓住（地上不延遲），每次延遲在 AirMax 附近隨機，最保守。");
        help("KnockbackDelay", "AirMax", "Air 模式每次大約壓多久（毫秒），實際在 0.75～1.35 倍之間隨機；也會被 ping 預算限制。");
        help("KnockbackDelay", "AirChance", "Air 模式在空中被打時有多少機率延遲。");
        help("KnockbackDelay", "SteadyDelay", "打架時所有封包晚多久（毫秒），不超過 LatencyBudget 減掉你的 ping。");
        help("KnockbackDelay", "Ramp", "Steady 延遲每 tick 最多變多少（毫秒）：開始和結束有多平順。");
        help("KnockbackDelay", "HoldTransactions", "Spike 且 HoldOthers 關掉時：壓住擊退後的交易確認封包也一起等。");
        help("KnockbackDelay", "RealtimeDamage", "自己受傷的封包照常即時到，比擊退早。關掉（預設）才像真的網路慢：受傷和擊退一起到，JumpReset 會在擊退時跳。");
        d("AutoHeal", "血量低時自動使用治療物品（湯、藥水、頭）。");
        d("BackTrack", "對手往後退時，暫時把他留在你最後看到的位置，讓攻擊還能打到伺服器記錄中的位置。");
        d("Hitflick", "改變你打出去的擊退方向：FLICK 打中瞬間甩開視角再轉回；HAZARD 靜默轉向附近的虛空或岩漿；DISPLACE 攻擊瞬間轉向虛空或指定角度。");
        help("Hitflick", "mode", "FLICK：甩開再轉回。HAZARD：原本的 KBDisplacement。DISPLACE：原本的 Displace。各自的設定在下面。");
        d("ServerLag", "把伺服器送來的封包延後處理，模擬伺服器延遲；受傷、血量、音效、聊天照常即時。可當成 FakeLag 的「收到延遲」一起用。");
        d("FakeLag", "延後送出自己的移動，讓伺服器上的你落後於實際位置，方便先手。");
        d("HitSelect", "挑出手時機：只在對自己有利（例如減少吃擊退）的時候讓攻擊送出。ACTIVE 也會管 Silent Aura 的攻擊。");
        d("MoreKB", "攻擊時重置衝刺，讓每一下打出更多擊退。");
        d("Criticals", "讓攻擊判定為爆擊（多 50% 傷害），以封包或小跳實現。");
        d("FastBow", "加快拉弓射箭的速度。");
        d("BlockHit", "攻擊之間自動舉劍格擋（block hit）。Slinky（預設）：按著左鍵、敵人在範圍內就舉劍，舉滿幾 tick 放下一 tick 讓攻擊打出去，再舉起來；可選只在快被打到時舉、放下時延遲封包。Manual：照你的點擊，打完舉劍一下。Predict：在你快能再被打到之前舉劍、被打到就放下。Full：照原版的擋劍節奏（放下、下一 tick 打、打完立刻舉起），只在能保護你的時候舉劍。Helper / Auto / Lag：其他方式。");
        help("BlockHit", "yuri-range", "Yuri：打到這個距離內（腳到腳）的目標才會格擋。");
        help("BlockHit", "yuri-chance", "Yuri：有多少比例的攻擊會接著格擋。");
        help("BlockHit", "yuri-combo-delay", "Yuri：打完幾 ms 後舉劍（那時要按著前進）。");
        help("BlockHit", "yuri-block-delay", "Yuri：劍舉著幾 ms。");
        help("BlockHit", "block-range", "敵人在這個距離內才自動舉劍（從眼睛到對方碰撞箱）。");
        help("BlockHit", "max-hold-ticks", "劍舉著最多幾 tick，之後放下一 tick 讓下一下攻擊打出去（1.8 舉劍時攻擊點擊會被吃掉）。");
        help("BlockHit", "require-left-click", "只在按著攻擊鍵時自動舉劍；沒按攻擊、只按右鍵時就是普通的手動格擋。");
        help("BlockHit", "require-right-click", "還要同時按著右鍵才自動舉劍。");
        help("BlockHit", "require-damage", "只在你的受傷時間剛好到 max-hurt-time 時舉劍（對方快能再打到你），被打中就放下。");
        help("BlockHit", "max-hurt-time", "require-damage 開啟時，受傷時間降到這個值的那個 tick 舉劍。");
        help("BlockHit", "fake-animation", "畫面上顯示舉劍動畫（只是本機畫面，不影響伺服器）。");
        help("BlockHit", "fake-animation-in-range", "假動畫只在敵人在範圍內時顯示。");
        help("BlockHit", "lag-chance", "放下劍時，有這個機率把之後送出的封包全部扣住。0 = 不扣。");
        help("BlockHit", "full-range", "Full：敵人在這個距離內（眼睛到碰撞箱）才會舉劍。");
        help("BlockHit", "full-style", "Full：Always＝敵人在範圍內就一直舉著（最安全、最慢）；Smart＝只在快被打到時、被盯上時、在空中時舉（減速少很多）。");
        help("BlockHit", "full-open-hurt", "Full Smart：被打之後，自己的受傷時間剩這麼多時舉劍（下一下快能打到你了）。");
        help("BlockHit", "full-ping-aware", "Full Smart：依 ping 提早舉劍，讓伺服器那邊來得及。");
        help("BlockHit", "full-hold", "Full Smart：每次至少舉幾 tick；之後一直舉著，直到被打到、敵人離開範圍或交戰結束。");
        help("BlockHit", "full-guard", "Full Smart：還沒開打時，範圍內有敵人面向你就短暫舉劍。");
        help("BlockHit", "full-air", "Full Smart：在空中時一直舉著（空中舉劍幾乎不會變慢，因為速度主要靠慣性）。");
        help("BlockHit", "full-log", "Full：寫 fullblock-*.txt（要開 Logs）：每次舉起/放下的原因、每次被打到有沒有擋到、舉劍時間佔比。");
        help("BlockHit", "lag-ticks", "扣住封包最多幾 tick，時間到就照順序全部送出。");
        help("BlockHit", "lag-flush-on-attack", "扣住期間一攻擊，就先把扣住的封包送出去，攻擊不會被延遲。");
        help("BlockHit", "lag-reblock", "扣住的封包一送出就馬上再舉劍。");
        d("AntiBounce", "卡在方塊裡時把你推出來：身體跟方塊重疊 2 tick 以上，就往最短的出口（優先你在走的方向）慢慢移出去，速度接近原版。Clutch 接方塊時不動作。");
        d("TargetOrbit", "用整齊排列、跟著敵人面向轉動的發光水晶碎片標示你正在打的人（Aura 的目標，或你最近打中、還在範圍內的玩家），身體也會發光。新目標時水晶飛入組成陣形，打中時有衝擊波並加速旋轉，擊殺時碎裂四散。選到這個模組時，選單旁邊會有即時預覽：拖曳旋轉、左鍵模擬打中、右鍵模擬擊殺。開任何 Aura 都會自動開啟；開著時不畫 TargetESP 和 Silent Aura 的方框。");
        help("TargetOrbit", "pattern", "Crown：三層水晶環＋一顆指向敵人面向的大水晶（預設）；Wings：背後的水晶翅膀；Helix：兩條沿身體往上的水晶螺旋；Shards：各自繞行的水晶；Orbit：光環＋繞行光點；Halo：腳下光環；Mark：頭上的水晶；Sigil：地上的魔法陣，前方有箭頭；Scan：全息掃描光柱，光環上下掃動；Lock：鎖定框＋血量弧。");
        help("TargetOrbit", "crystals", "Crown、Wings、Helix、Shards：水晶數量。");
        help("TargetOrbit", "crystal-size", "Crown、Wings、Helix、Shards：每顆水晶的大小。");
        help("TargetOrbit", "body-glow", "在被標示的人身上疊一層標示顏色的光。");
        help("TargetOrbit", "glow-strength", "身體發光的亮度。");
        help("TargetOrbit", "combat-range", "沒開 Aura 時：你打的人要在這距離內才會標示。");
        help("TargetOrbit", "combat-time", "沒開 Aura 時：最後一次打中後幾秒內才會標示。");
        help("TargetOrbit", "preview", "沒有其他標示對象時，標示 8 格內最近的玩家，不打架也能在遊戲裡看效果。");
        help("TargetOrbit", "color", "Theme：跟 TargetColors 主題色；Health：血量一半以下慢慢變紅；Custom：用 custom-color。");
        help("TargetOrbit", "custom-color", "Custom：標示的顏色。");
        help("TargetOrbit", "core-color", "亮點顏色：水晶和線條的中心、箭頭、掃描亮環、打中時的閃光。");
        help("TargetOrbit", "glow-color", "身體發光的顏色（不跟標示同色時）。");
        help("TargetOrbit", "glow-follows-mark", "身體發光跟標示同色；關掉就用 glow-color。");
        help("TargetOrbit", "hit-rings", "打中時往外的環：地上衝擊波、Sigil 的擴散環、Scan 往下掉的環。");
        help("TargetOrbit", "size", "標示的大小。");
        help("TargetOrbit", "speed", "旋轉的速度。");
        help("TargetOrbit", "comets", "Orbit：繞行光點的數量（0 = 只有光環）。");
        help("TargetOrbit", "trail", "Orbit：光點尾巴長度。");
        help("TargetOrbit", "opacity", "整體透明度。");
        help("Scaffold", "rotation-random", "每次放方塊時視角在幾度內緩慢飄移，不會每次都看同一個角度；飄移後放不到方塊時就用準確的角度。0 = 關。");
        help("AntiBounce", "speed", "每 tick 最多推多遠。原版自己推出方塊是 0.1；太快可能被當成穿牆。");
        help("AntiBounce", "max-escape", "最多找多遠的出口。超過就不推，只記錄。");
        help("AntiBounce", "log", "每次卡住寫一行到 config/Atlas/stuck-*.txt：卡在哪個方塊、是不是自己剛放的、離上次被拉回多久、延遲、怎麼出來的。");
        help("BlockHit", "ManualChance", "每次打中後舉劍一下的機率（每秒格擋次數 = CPS × 機率）。Silent Aura 自己有格擋時不作用。");
        d("ThrowAura", "自動把雪球、蛋等投擲物丟向敵人。");
        d("Displace", "攻擊瞬間轉向，把對手的擊退方向導向虛空或指定方向。");
        help("Displace", "Keep-hitbox", "打人的那一下伺服器那邊的視角只轉到還能打到對手碰撞箱的角度（Grim、Polar 會檢查視角打不打得到）；轉太多那一下就不算。開著時只能偏 5～10 度，而且只影響疾跑和擊退附魔那部分的擊退，在 Pika、Grim 這類伺服器上效果很小。關掉＝照設定的角度轉，只適合不檢查攻擊視角的伺服器。");
        d("KeepSprint", "攻擊後不讓衝刺被打斷，保持移動速度。");
        d("PlainAura", "精簡版自動攻擊：只在實際送出的視角真的對準目標時才出手，最不容易被抓。");
        d("InvulnTiming", "算準目標無敵時間結束的時機再出手，並把多餘的攻擊轉給打得動的人。");
        d("KeepRange", "連擊中自動放開前進（S-tap），避免貼太近浪費對手的擊退。");
        d("AimBacktrack", "剛好揮空的一刀，改算到前幾個 tick 準心對到的人身上。");
        d("KBDisplacement", "手動打人時，靜默把擊退方向轉向附近的危險（虛空、岩漿）。");
        // Ported from OpenSkid (2026-10-05)
        d("KeyStrokes", "在畫面上顯示 WASD、空白鍵和滑鼠按鍵（含 CPS）。可在 HUD 編輯器拖曳。");
        d("PotionHUD", "在畫面上列出目前的藥水效果和剩餘時間。可在 HUD 編輯器拖曳。");
        d("InventoryHUD", "在畫面上顯示身上的裝備和快捷欄物品。可在 HUD 編輯器拖曳。");
        d("PlayerList", "在畫面上顯示自訂的玩家列表（可顯示延遲、排序、篩選模式）。可在 HUD 編輯器拖曳。");
        d("ClosestPlayerHUD", "顯示每一隊離你最近的玩家：距離、人數、高低差、血量。可在 HUD 編輯器拖曳。");
        d("FKCounter", "從聊天訊息統計每一隊的最終擊殺數（Final Kill）。可在 HUD 編輯器拖曳。");
        d("BedPlates", "在床上顯示彩色標示，以及床周圍的防禦方塊。");
        d("TNTTimer", "在點燃的 TNT 上方顯示倒數秒數。");
        d("DamageTags", "打到實體時，在它上方飄出傷害數字。");
        d("ItemTags", "在掉落物上方顯示物品名稱和數量。");
        d("EntityCulling", "不繪製被擋住、看不到的實體，提高 FPS。");
        d("Notifications", "右下角顯示動畫通知（模組開關等），可設定開關音效。可在 HUD 編輯器拖曳。");
        d("ExploitFixer", "擋下你自己送出的異常資料包和聊天封包（防禦用）。");
        d("HitBox", "放大其他實體的碰撞箱，比較容易打中，可選擇顯示放大的範圍。");
        d("LagRange", "靠近對手時延遲自己的封包，讓伺服器看到的你慢一步。進到近戰距離就逐漸放出，攻擊時幾乎沒有壓著的；攻擊、被擊退或被拉回時立刻放出並暫停。");
        help("LagRange", "range", "什麼時候壓：對手在這距離內、而且你正在靠近。");
        help("LagRange", "melee-distance", "什麼時候放：最近只壓到這個距離。它會自己更早放：攻擊距離 + 接近速度 × 佇列放完要的 tick，讓第一刀能打到之前佇列就清空。");
        help("LagRange", "weapons-only", "什麼時候壓：只有拿武器時。");
        help("LagRange", "allow-tools", "什麼時候壓：工具也算武器。");
        help("LagRange", "bot-check", "忽略假人。");
        help("LagRange", "teams", "忽略隊友。");
        help("LagRange", "delay", "壓多久：伺服器看到你慢多少（毫秒，以 tick 計）。");
        help("LagRange", "let-out", "什麼時候放：Gradual 每 tick 放約兩個；At once 一次全部放出。");
        help("LagRange", "recoil-time", "什麼時候放：攻擊、挖、被擊退之後這段時間不壓（毫秒）。");
        help("LagRange", "flag-pause", "什麼時候放：被伺服器拉回之後這段時間不壓（毫秒）。");
        help("LagRange", "show-position", "第三人稱時畫出伺服器上你的位置。");
        help("LagRange", "log", "File：每次壓和放的原因寫進 config/Atlas/lagrange-*.txt。");
        help("LagRange", "release-on-sprint-reset", "什麼時候放：W-tap（停止疾跑）時立刻全部放出，接著的攻擊從你真正的位置算。（來源：Slinky）");
        help("BackTrack", "only-in-combat", "只在你打中這個對手後 1 秒內才拖住他（預設開），整場打架不會一直扣封包。（來源：Slinky）");
        help("BackTrack", "require-attack-key", "只在按著攻擊鍵時才拖住對手。（來源：Slinky）");
        help("KnockbackDelay", "RequireAttackKey", "只在按著攻擊鍵時才延後擊退。（來源：Slinky）");
        help("KnockbackDelay", "OnlyInAir", "只在空中時才延後擊退，在地上照常。（來源：Slinky）");
        help("Blink", "release-on-attack", "扣住時攻擊實體，先把扣住的封包全部放出再攻擊。（來源：Slinky）");
        help("Blink", "release-on-dig", "扣住時挖方塊，先把扣住的封包全部放出再挖。（來源：Slinky）");
        help("Timer", "require-attack-key", "只在按著攻擊鍵時加速，其他時候 1.0。（來源：Slinky）");
        help("Timer", "require-damage", "只在受傷時間內加速。（來源：Slinky）");
        help("Timer", "require-falling", "只在下墜時加速。（來源：Slinky）");
        help("ServerLag", "Lag ms", "收到的封包延後多久（毫秒）。最低 25，可以當成輕量的「收到延遲」，和 FakeLag（送出延遲）一起開。");
        help("ServerLag", "only-in-combat", "只在你上次出手後 1 秒內才延遲。（來源：Slinky）");
        help("ServerLag", "weapons-only", "只在拿著劍時才延遲。");
        d("NoHitDelay", "移除攻擊後的點擊冷卻（1.8 揮空延遲）。");
        d("Piercing", "準心可以穿過方塊或隊友，直接選中後面的目標。");
        d("ClickAssits", "在你點擊時額外補點，讓 CPS 更穩定。");
        d("SprintReset", "打中時自動重置衝刺（W-tap 等），增加擊退。");
        d("Reach", "增加攻擊距離（伺服器容易偵測，慎用）。");
        d("TickBase", "操控 tick 時序，在進入攻擊範圍的瞬間先跑幾個 tick 搶先手。");
        d("TimerRange", "接近目標時短暫加速遊戲時鐘，搶先進入攻擊距離。");
        help("TimerRange", "mode", "NORMAL/SMART/MODERN：原本的（先凍結，再加速一段時間）。SLINKY：OpenSlinky 的 Regular，預測準星會碰到對手時先多跑幾 tick，再停住同樣久。LEGIT：OpenSlinky 的 Legit，先停住並壓住送出的封包，再一次補跑。");
        help("TimerRange", "min-dist", "SLINKY/LEGIT：離對手比這更近就不觸發。");
        help("TimerRange", "duration", "SLINKY：最多先跑幾 tick。");
        help("TimerRange", "duration-legit", "LEGIT：最多停住幾 tick（1-4）。");
        help("TimerRange", "cooldown", "SLINKY/LEGIT：兩次之間的冷卻（秒）。");
        help("TimerRange", "block-while-frozen", "SLINKY：停住時舉劍格擋。");
        help("TimerRange", "require-mouse-pressed", "SLINKY/LEGIT：要按著攻擊鍵。");
        help("TimerRange", "require-weapon", "SLINKY/LEGIT：要拿劍。");
        help("TimerRange", "require-not-using-item", "SLINKY/LEGIT：使用物品時不觸發。");
        d("Velocity", "減少或改變自己吃到的擊退。");
        d("Humanization", "讓戰鬥模組像人一樣：會揮空、會瞄偏、有反應時間。全域設定，Silent Aura 等模組都能套用。");
        help("Humanization", "preset", "一次填好所有數值：Legit（40%）、Good（55%）、Tryhard（70%）。之後每項都還能改；Custom 不會覆蓋。");
        help("Humanization", "apply-silent-aura", "套用到 Silent Aura（LiquidBounce 和 Atlas 引擎）。");
        help("Humanization", "hit-rate", "目標命中率：點擊中真正打到的比例。真人大約 40-60%。");
        help("Humanization", "hit-rate-spread", "命中率會在目標上下慢慢漂移的幅度（百分點）。");
        help("Humanization", "correction", "實際命中率偏離目標時拉回去的力道；0 = 純擲骰。");
        help("Humanization", "streaks", "手熱、手冷：一段時間連中或連空。");
        help("Humanization", "distance-penalty", "超過 distance-start 之後，每多一格扣幾個百分點。");
        help("Humanization", "strafe-penalty", "目標每橫移 0.1 格/tick 扣幾個百分點。");
        help("Humanization", "hurt-penalty", "剛被打的 hurt-ticks 內扣的百分點（逐漸恢復）。");
        help("Humanization", "max-hits-per-second", "每秒最多打中幾下（0 = 不限）。");
        help("Humanization", "miss-aim-off", "揮空方式的權重：瞄偏（那一 tick 視角真的偏出碰撞箱）。最像真人。");
        help("Humanization", "miss-swing-only", "權重：視角對準、只揮不打。");
        help("Humanization", "miss-skip", "權重：這一下不點。");
        help("Humanization", "aim-off-min", "瞄偏時越過碰撞箱邊緣的最小距離（格）。");
        help("Humanization", "miss-cooldown", "揮空後照原版 1.8 等 10 tick 才能再點。");
        help("Humanization", "reaction-new-min", "看到新目標到開始瞄準的反應時間（ms）。");
        help("Humanization", "tracking-noise", "瞄準時手的微抖（度），目標動越快抖越大。");
        help("Humanization", "overshoot-chance", "大角度轉頭時轉過頭再拉回的機率。");
        help("Humanization", "lapse-chance", "每秒短暫走神、停止跟隨目標的機率。");
        help("Humanization", "speed-sigma", "每 tick 轉頭速度的對數常態波動。");
        help("Humanization", "human-clicks", "aura 的點擊節奏改用 AutoClicker 的 Human。");
        help("Humanization", "show-stats", "畫面上顯示最近 30 秒的命中率、揮空方式、反應時間。");
        help("Velocity", "lag-ticks", "Lag：擊退（和之後的 transaction）延後幾 tick 才交給遊戲。伺服器看到的是 ping 短暫變高、擊退晚到，就像延遲。");
        help("Velocity", "lag-chance", "Lag：幾成的擊退會延後。");
        help("Velocity", "lag-explosions", "Lag：爆炸也一起延後。");
        help("Velocity", "lag-ping-budget", "Lag：ping 加延後不超過這個值（ms）；會自動縮短，ping 太高就不延後。0＝不限制。");
        help("Velocity", "lag-cooldown", "Lag：放出後隔多久（ms）才能再延後，連段時不會一直卡著。");
        help("Velocity", "lag-only-in-fight", "Lag：6 格內有其他玩家（不是 bot、不是隊友）才延後。");
        help("Velocity", "polar-counter-chance", "Polar：被擊退時，在這一 tick 移動之前疾跑反擊的機率（需要 Silent Aura 有目標，且沿著伺服器知道的角度在 3.0 內打得到）。原版的攻擊會讓你自己的速度少 40%。");
        help("Velocity", "polar-jump-chance", "Polar：在地上被擊退時，當下那一 tick 起跳（跳躍重置）的機率。");
        help("Velocity", "polar-both-chance", "Polar：兩種都能做時，兩種一起做的機率；否則只做其中一種。");
        help("Velocity", "polar-react-min", "Polar：反擊最少等幾 tick（0 = 被打的那一 tick）。");
        help("Velocity", "polar-react-max", "Polar：反擊最多等幾 tick。");
        help("Velocity", "polar-max-rate", "Polar：最近 10 次擊退中，最多處理這個比例。");
        help("Velocity", "polar-max-streak", "Polar：連續處理的次數會在這個數的一半到這個數之間斷掉。0 = 不限。");
        help("Velocity", "polar-min-interval", "Polar：兩次處理之間最短間隔（毫秒）。");
        help("Velocity", "polar-only-in-fight", "Polar：6 格內有其他玩家（不是 bot、不是隊友）才作用。");
        d("Wtap", "打中後真的放開 W 鍵再按回去（W-tap），由遊戲自己重置衝刺，下一下擊退更大。");
        help("Wtap", "chance", "打中後有多少機率 W-tap。");
        help("Wtap", "release-delay", "打中後幾毫秒放開 W（每次隨機 ±20%）。");
        help("Wtap", "re-press-delay", "W 放開多久再按回去（毫秒，每次隨機 ±20%）。");
        help("Wtap", "select-hits", "只在這一下打得出傷害時才 W-tap（對方大部分無敵時間已過）。");
        d("AutoGapple", "血量低於設定百分比時自動吃金蘋果。");
        d("AutoTool", "挖方塊時自動切到最適合的工具。");
        // ------------------------------------------------------------ PLAYER
        d("Blink", "暫時扣住自己送出的移動封包，之後以伺服器能接受的節奏放出（瞬移般的效果）。");
        d("Clutch", "被打下橋或走到虛空邊緣時，自動轉頭放方塊把自己接住。");
        help("Clutch", "turn-random", "轉頭速度每 2～4 tick 隨機變化的幅度（%）：快來不及接時只會更快、不會更慢；往下看很陡時，左右也會輕微飄動。0 = 關。");
        help("Clutch", "snapback-style", "轉回原本視角的方式：Even＝平均分成幾 tick 轉回去（最後一 tick 剛好對準），Speed＝每 tick 固定轉幾度（舊的）。");
        help("Clutch", "snapback-ticks", "Even：分幾 tick 轉回原本的視角。");
        help("Clutch", "snapback-delay-random", "轉回視角前，再隨機多等 0 到這麼多 tick。");
        d("NoFall", "避免摔落傷害。");
        d("AntiVoid", "快掉進虛空時自動把你拉回或接住。");
        d("AutoSwap", "方塊、投擲物、珍珠等用完時，自動換到同類物品的格子。");
        d("InvManager", "自動整理背包：穿最好的裝備、丟垃圾、把武器工具方塊放到固定格子。");
        d("Refill", "打開背包時，自動把湯或藥水補到快捷欄。");
        d("AntiAFK", "自動做一些小動作，避免被伺服器判定掛機踢出。");
        d("AntiFireball", "自動轉頭打掉飛向你的火球。");
        d("AutoAnduril", "定時切到 Andúril 劍拿速度效果，再切回原本的物品。");
        d("InventoryClicker", "在背包畫面按住滑鼠拖過格子時，自動快速點擊。");
        // ---------------------------------------------------------- MOVEMENT
        d("Fly", "飛行。");
        d("Speed", "加快移動速度。");
        d("LongJump", "跳得更遠。");
        d("Jesus", "可以在水面上行走。");
        d("NoSlow", "使用物品（格擋、吃東西、拉弓）時不減速。");
        help("NoSlow", "HW Open Hurt", "Hit-Window：在來回延遲之外再提前幾個 hurtTime 舉劍；舉劍時機 = 這個值 + ping 換算的 tick，讓下一刀能打到時劍剛好舉著。");
        help("NoSlow", "HW Ping Aware", "Hit-Window：依你的來回延遲提早舉劍（伺服器晚這麼久才收到）。");
        help("NoSlow", "HW Hold", "Hit-Window：每次在伺服器上舉劍幾 tick；被打中就立刻放下。只有這幾 tick 會照原版減速。");
        help("NoSlow", "HW Guard", "Hit-Window：還沒被打之前，附近有敵人面向你時，短暫舉劍防第一刀。");
        help("NoSlow", "HW Guard Range", "Hit-Window：那個敵人要多近才算。");
        help("NoSlow", "HW Log", "Hit-Window：每次舉劍和每一刀有沒有擋到，寫進 config/Atlas/noslow-*.txt。");
        help("NoSlow", "HW Aura Blocks", "Hit-Window：也接管 aura 或 AutoBlock 舉的劍（Silent Aura 的 AutoBlocking、AutoBlock 模式），不只你自己按右鍵的格擋。");
        help("NoSlow", "HW Priority", "Hit-Window 接管 Silent Aura 的格擋時誰讓誰：Hit = 只有 aura 下一下至少 3 tick 後才舉劍，並在出手前一 tick 放下（不耽誤出手）；Block = 舉劍時 aura 先不出手。");
        help("HUD", "hud-theme", "所有 HUD 的外觀。JELLO：LiquidBounce JelloBounce 主題（黑 45%、柔和陰影、圓角卡片、模組清單貼齊）。CLASSIC：各 HUD 原本的畫法。");
        // 手機安全模式（2026-10-15，util.render.MobileSafe）：Client Settings > Performance > mobile-safe
        help("HUD", "blur", "背景模糊。手機安全模式（Client Settings > Performance > mobile-safe，手機 gl4es / Krypton Wrapper 自動開啟）下不畫。");
        help("HUD", "glow", "背景光暈。手機安全模式下不畫。");
        help("ESP", "mode", "畫法。OUTLINE 用著色器和額外畫面緩衝；手機安全模式（Client Settings > Performance > mobile-safe）下改畫 3D 框。");
        d("NoDelay", "移除各種延遲：攻擊、點擊、跳躍。");
        d("MLG", "墜落時用水或梯子自救。");
        d("BlockESP", "標出箱子、床或自訂方塊的位置。");
        d("AutoMessage", "遊戲結束或擊殺後自動發送訊息。");
        d("NoItemRelease", "放開右鍵時不送出「停止使用物品」封包，伺服器仍當你在使用；本機照常停止。照 Slinky 的做法：看實際送出的封包，使用物品後的下一個放開封包不送。");
        d("FastAccel", "起步更快：在地上時，把速度往「按著這些鍵最後會達到的速度」推近一部分。不會超過正常按鍵能到的速度，只是更快到。（來源：Slinky）");
        help("FastAccel", "boost", "每 tick 把剩下的差距補上多少。0% = 原版，100% = 一步到位。");
        help("FastAccel", "require-not-damaged", "受傷（擊退中）時不作用。");
        help("FastAccel", "require-weapon", "只在拿著劍或斧時作用。");
        d("InstantStop", "放開 W 時立刻停下：在地上放開前進鍵的那一 tick，自動點一下後退（或後左／後右）把剩下的速度抵銷，下一 tick 還給你。（來源：Slinky）");
        d("NullMove", "相反方向鍵以最新按的為準（snap tap）：按著 A 再按 D 會往右走，不會停住；放開 D 若還按著 A 就回到往左。W/S 也一樣。（來源：Slinky）");
        d("Strafe", "讓移動方向更快轉向你按的方向：每 tick 把速度往按鍵方向拉一部分，地面、跳躍、空中分開設定。（來源：Slinky）");
        help("Strafe", "jump", "起跳那一 tick 的轉向強度。");
        help("Strafe", "ground", "在地上的轉向強度。");
        help("Strafe", "air", "在空中的轉向強度。0% = 原版慣性，100% = 立刻轉向。");
        help("Strafe", "max-hurt-time", "受傷時間大於這個值時不作用（被打飛時不改方向）。10 = 永遠作用。");
        help("Strafe", "require-weapon", "只在拿著劍或斧時作用。");
        d("Pointers", "準心周圍的箭頭，指向附近玩家所在的方向；可依距離、名牌顏色或自訂顏色上色，3D 模式會隨你的俯仰角傾斜。（來源：Slinky）");
        help("Pointers", "range", "多遠內的玩家才顯示箭頭（格）。");
        help("Pointers", "ignore-within-fov", "在準心這個角度內的玩家不顯示（已經看得到的人）。0 = 全部顯示。");
        help("Pointers", "hide-friendlies", "不顯示好友和隊友。");
        help("Pointers", "style", "2D：平面圓圈。3D：圓圈像平放在地上，隨你的視角傾斜。");
        help("Pointers", "colors", "Distance：近的用近色、遠的用遠色、中間漸變。Name tag：用對方名牌（隊伍）顏色。Manual：敵人／友方各一色。");
        help("Pointers", "near", "Distance 模式：這個距離內是近色。");
        help("Pointers", "far", "Distance 模式：這個距離外是遠色。");
        help("Pointers", "scale", "箭頭大小。");
        help("Pointers", "radius", "圓圈半徑。");
        help("Pointers", "distance-radius", "越遠的人箭頭離準心越遠。");
        d("PingFix", "延遲類功能扣住封包時，Keep-Alive 封包照常立刻送出／處理，Tab 顯示的 ping 維持你的真實 ping，不會跟著暴增。會讓 PingSpoof 和 Disabler 扣不住 Keep-Alive。（來源：Slinky）");
        d("ItemUseFix", "挖方塊時也能使用物品（吃東西、舉劍、丟東西）：原版挖方塊時右鍵會被忽略。（來源：Slinky）");
        help("KeepSprint", "motion-min", "打中人時保留多少速度（%，每下從 min～max 之間隨機）。100＝完全不減速，60＝原版。參考 LiquidBounce NextGen。");
        help("KeepSprint", "motion-max", "同上，範圍的上限。");
        help("KeepSprint", "motion-when-hurt-min", "自己在被打狀態（hurt-time 範圍內）時，打中人保留多少速度（%）。");
        help("KeepSprint", "motion-when-hurt-max", "同上，範圍的上限。");
        help("KeepSprint", "hurt-time-min", "自己的受傷 tick 在這個範圍內時，改用 motion-when-hurt。");
        help("KeepSprint", "hurt-time-max", "同上，範圍的上限。");
        help("KeepSprint", "chance", "KeepSprint 生效的機率；沒生效的那一下就是原版（減速、斷衝刺）。");
        help("KeepSprint", "hit-control", "進階：打中人的那個 tick 額外調整速度與衝刺（下面幾個 Hit 選項）。預設關；舊版這段每個 tick 都在跑，會一直減速、斷衝刺。");
        help("NoSlow", "NG Timing", "NG-Reuse／NG-Switch 送封包的時機：PreAndPost＝移動封包前放開、之後再用；Pre／Post＝都在那一邊送。");
        help("NoSlow", "NG Blink MaxTicks", "NG-Blink 格擋時最多把移動封包扣住幾個 tick，到了就先送出去。");
        help("NoSlow", "Sword Mode", "格擋時的模式。Grim-Alternate（新）：一個 tick 全速、下一個 tick 照原版減速，交替進行，不送任何封包也不扣封包。Grim 只在連續兩個 tick 速度不對才判定 NoSlow，所以交替不會被抓；速度約為正常的 60%（原版只有 20%）。這個模式不吃 Motion 設定：Grim 的 Simulation 只模擬「全速」和「原版減速」兩種，中間值每個 tick 都會被 Simulation 抓。");
        help("NoSlow", "Food Mode", "吃東西、喝藥水時的模式。Grim-Alternate：同劍的說明，全速和減速 tick 交替。");
        help("NoSlow", "Bow Mode", "拉弓時的模式。Grim-Alternate：同劍的說明，全速和減速 tick 交替。");
        help("NoItemRelease", "safe-release", "一移動或疾跑，就在該 tick 的移動封包前補送放開封包（避免 Grim NoSlow）；預設關閉，照 Slinky 一直不送。");
        help("NoItemRelease", "mode", "SWORD：只擋劍的放開（預設，同 Slinky）。CONSUMABLE：只擋食物、藥水等。ALL：兩者都擋。弓和釣竿永遠不擋。");
        help("NoItemRelease", "let-module-releases", "開：其他模組刻意送的放開（Silent Aura 打之前放下劍、NoSlow）照常送出，只擋你自己放開右鍵的那個。關：全部都擋，完全照 Slinky。");
        d("NoJumpDelay", "移除連續跳躍之間的延遲。");
        d("SafeWalk", "走到方塊邊緣時不會掉下去（像一直按著潛行）。");
        d("Eagle", "BridgeAssist：走到邊緣時自動潛行，用來後退搭橋（原名 Eagle）。");
        d("InvWalk", "開著背包或選單時也能移動。");
        d("TargetStrafe", "繞著目標轉圈移動。");
        d("MoveFix", "靜默轉頭時修正移動方向，讓移動跟伺服器看到的視角一致。");
        d("Stasis", "短暫凍結自己的動量，之後再放開。");
        d("Timer", "改變遊戲時鐘速度（整體加速或減速）。");
        // ------------------------------------------------------------- WORLD
        d("Scaffold", "自動在腳下放方塊搭橋，支援疊高（tower）、保持高度與多種轉頭模式。");
        d("AutoBlockIn", "自動用方塊把自己圍起來：先圍身體一圈，再補屋頂。");
        help("AutoBlockIn", "debug", "開發用：把每一步的判斷寫進 config/Atlas/placedebug-*.txt（背景寫入，不影響 fps）。平常不用開。");
        help("AutoBlockIn", "debug-chat", "debug 時也印到聊天欄（每秒最多 4 行）。裝了聊天相關模組時很吃 fps，只在需要即時看時才開。");
        d("AutoBedDef", "自動在自己的床周圍放方塊防守。");
        help("AutoBedDef", "own-bed-only", "只守這個世界裡第一張找到的床（你出生在它旁邊）。關掉：守最近的床，敵人的也算。");
        help("AutoBedDef", "wool-only", "只用羊毛。關掉：沒有羊毛時用任何完整的實心方塊。");
        d("AutoHeadHitter", "跳躍時頭頂碰到方塊會自動調整，維持頂頭跳加速。");
        d("BedNuker", "自動挖附近敵方的床（可以先挖外圍的保護方塊）。");
        help("BedNuker", "outside-first", "從外面開始挖：一層一層挖你和床之間最先擋住的方塊，直到床露出來。關掉時挖貼著床、最好挖的那塊。");
        d("ChestAura", "自動打開附近的箱子。");
        d("ChestStealer", "打開箱子時自動把有用的東西拿走。");
        d("FastPlace", "加快放方塊的速度。");
        d("SpeedMine", "加快挖方塊。BreakSpeed：工具原本的挖掘速度乘上 multiplier（1.4 約快 40%）。FinishEarly：挖到一定進度就提早完成，speed 100% 是原版伺服器能接受的最快（原版時間的 70%）。");
        d("AntiObbyTrap", "頭卡在黑曜石等陷阱方塊裡時，把那格當成空氣，讓你看得到、出得去。");
        // ------------------------------------------------------------ RENDER
        d("ESP", "在其他玩家身上畫外框或光暈，隔牆也看得到。");
        d("Chams", "隔牆顯示玩家模型。");
        d("NameTags", "放大並強化名牌：顯示血量、距離、裝備與附魔。");
        d("Tracers", "從畫面中央畫線指向其他玩家。");
        d("TargetESP", "在目前的攻擊目標身上畫標記。");
        d("TargetHUD", "顯示目前目標的資訊面板（血量、頭像等）。");
        d("ItemESP", "標出地上掉落的物品（綠寶石、鑽石、金、鐵）。");
        d("ChestESP", "標出箱子、陷阱箱和終界箱的位置。");
        d("BedESP", "標出床的位置。");
        d("Xray", "透視方塊，標出礦物和指定方塊。");
        d("ViewClip", "第三人稱視角時鏡頭可以穿過方塊。");
        d("Trajectories", "顯示弓箭、投擲物、珍珠的預測軌跡。");
        d("BlockOverlay", "強化準心指著的方塊外框。");
        d("BreakProgress", "顯示方塊被挖的進度。");
        d("Indicators", "在畫面邊緣提示飛向你的火球、珍珠、箭等投擲物。");
        d("Radar", "小地圖雷達，顯示附近玩家的方向與距離。");
        d("LatencyCrosshair", "多畫一個準心，標出伺服器目前認為你瞄準的位置（延遲造成的落差）。");
        d("TeamHealthDisplay", "顯示隊友或敵人的血量。");
        d("RenderFixes", "較現代的聊天框與計分板外觀（圓角、可移動）。");
        d("AntiDebuff", "移除失明、反胃等畫面效果。");
        // -------------------------------------------------------------- MISC
        d("AntiBot", "辨識伺服器的假人（NPC、反作弊機器人），讓其他模組略過它們。");
        d("TargetFilter", "統一判斷「誰算目標」（好友、隊友、假人、隱身、距離），給戰鬥與延遲類模組共用。");
        d("MCF", "中鍵點玩家加入或移除好友。");
        d("NickHider", "隱藏自己的名字（聊天、計分板、等級）。");
        d("Spammer", "定時在聊天欄發送訊息。");
        d("AutoAuth", "自動輸入伺服器的登入／註冊密碼。");
        d("AutoHypixel", "Hypixel 小工具：自動接受規範、自動下一場等。");
        d("BedwarUtils", "Bedwars 輔助：鑽石升級、物品追蹤、隱身提醒、珍珠／接近提醒等 HUD。");
        d("BedTracker", "追蹤床的狀態，敵人靠近自己的床時提醒，可自動發訊息。");
        d("LightningTracker", "在聊天欄報出閃電落下的座標（常代表有人死亡）。");
        d("ESPDetector", "偵測隔牆一直把準心跟著你的玩家（疑似開 ESP）。");
        d("AntiCheat", "偵測從過遠距離或隔牆打你的玩家並回報。");
        d("AntiObfuscate", "移除文字的亂碼效果（§k），讓被混淆的字看得清楚。");
        d("MouseRawInput", "使用原始滑鼠輸入，不受系統滑鼠加速影響。");
        d("ResourceSpoofer", "回應伺服器的材質包要求但不真的載入，並隱藏模組專用的頻道。");
        d("NoRotate", "伺服器拉回你的位置時，不讓它改掉你的視角。");
        // ----------------------------------------------------------- EXPLOIT
        d("Disabler", "針對特定反作弊的繞過手段集合（多數只對特定伺服器有效，可能被抓）。");
        d("GhostHand", "準心可以穿過隊友，直接點到後面的東西。");
        d("ClientSpoofer", "偽裝回報給伺服器的客戶端品牌。");
        // ------------------------------------------------------------ CLIENT
        d("ClickGUIModule", "點擊選單的樣式設定：外觀、顏色、視窗大小。");
        d("GuiModule", "開啟點擊選單（預設右 Shift）。");
        d("FlagDetector", "偵測伺服器的拉回、方塊被退回、傷害被減，並推斷是哪個模組造成的。");
        d("FlagResponder", "某個模組一直被伺服器拉回時，自動把它關掉。");
        d("HitCheck", "統計哪幾刀真的有打到，並解釋沒打到的原因。");
        d("FightLog", "每場對戰記一行：命中、連擊、擊退、勝負。");
        d("Debug", "所有診斷工具的總開關。");
        d("Logs", "config/Atlas 診斷紀錄的總開關。關（預設）：只寫 flags 紀錄（拉回等伺服器修正）。開：所有模組的測試紀錄都寫。每次啟動時刪除超過 keep-days 天的舊紀錄。");
        help("Logs", "keep-days", "啟動遊戲時，刪除超過這麼多天的紀錄。");
        d("PacketLogger", "記錄封包，被踢出時寫成檔案方便查原因。");
        d("ServerFingerprint", "記錄伺服器透露的資訊，推測它用哪種反作弊。");
        d("AutoTune", "自動試不同的設定值，保留實測表現較好的。");
        d("Adaptive", "長時間學習這個伺服器會抓哪些模組，逐步把它們調弱。");
        d("ServerProfiles", "依照進入的伺服器自動載入對應的設定檔。");
        d("LatencyGovernor", "網路變差時，自動把對延遲敏感的設定調保守。");
        d("Panic", "一鍵關掉所有功能模組，並放出所有被扣住的封包。");
        d("Rotations", "共用轉頭引擎的參數：速度隨機、弧線路徑、接近目標時減速。");
        // -------------------------------------------------------------- LEGIT
        d("HUD", "畫面上的模組清單與介面外觀（顏色、背景、模糊、光暈、字型）。");
        d("Hotbar", "自訂快捷欄的外觀。");
        d("FPScounter", "在畫面上顯示 FPS。");
        d("WaterMark", "畫面上的客戶端浮水印。");
        d("DynamicIsland", "仿動態島的資訊條（目前狀態、通知）。");
        d("Statistics", "這次連線的時間、擊殺與勝場統計。");
        d("PlayTracker", "小 HUD：記錄玩了多久、玩了幾場（含今天累計、勝敗），並在疑似被減傷時提醒。");
        d("Ambience", "改變世界的時間與天氣（只有自己看得到）。");
        d("FullBright", "全亮，暗處也看得清楚。");
        d("NoHurtCam", "受傷時畫面不搖晃（或減少搖晃）。");
        d("Sprint", "自動衝刺。");
        d("FreeLook", "按住按鍵時可以自由轉動鏡頭，身體方向不變。");
        d("ItemPhysics", "掉落物平躺在地上，空中會翻滾。");
        d("Capes", "顯示自訂披風。");
        d("Accessories", "在玩家頭上畫頭飾與翅膀，只有你看得到。");
        d("AutoRecraft", "背包打開時自動用合成欄補做蘑菇湯。");
        d("Freeze", "把來襲的擊退封包扣住不動，時間到才一次放行。");
        d("Animations", "自訂揮劍與格擋動畫。");
        d("HitParticleEffects", "打中時的粒子效果，擊殺時可播音效。");
        d("LegitHUD", "座標、FPS、延遲、CPS 與按鍵顯示。");
        d("ArmorHUD", "顯示身上的護甲與耐久。");
        d("EffectsHUD", "顯示目前的藥水效果與剩餘時間。");
        d("AutoRespawn", "死亡後自動重生。");
        // ------------------------------------------------------------- THEME
        d("Theme", "主題總開關與主色；各主題群組可以統一或個別上色。");
        d("PlayerColors", "主題群組：玩家 ESP 與 2D ESP 的顏色。");
        d("TracerColors", "主題群組：Tracer 線與箭頭的顏色。");
        d("TargetColors", "主題群組：攻擊目標標記的顏色。");
        d("BacktrackColors", "主題群組：Backtrack 顯示真實位置的方框顏色。");
        d("BedColors", "主題群組：床 ESP 的顏色。");
        d("ChestColors", "主題群組：箱子 ESP 的顏色。");
        d("ItemColors", "主題群組：掉落物 ESP 的顏色。");
        d("BlockColors", "主題群組：準心方塊外框的顏色。");
        d("ProjectileColors", "主題群組：投擲物軌跡與來襲提示的顏色。");
        d("InterfaceColors", "主題群組：HUD 模組清單等介面的顏色。");
        d("ChamsColors", "主題群組：Chams 模型的顏色。");
        d("NameTagColors", "主題群組：名牌背景與外框的顏色。");
        d("WidgetColors", "主題群組：快捷欄、雷達等小工具的顏色。");
        d("EffectColors", "主題群組：打擊粒子的顏色。");

        // ============================================================ GROUPS
        g("KillAura2",
                h("常用", "mode", "min-cps", "max-cps", "attack-range", "auto-block", "legit-threat", "legit-tolerance", "block-min-ticks", "block-stop-sprint", "block-release", "block-log", "move-fix"),
                h("目標", "sort", "switch-delay", "scan-extra", "fov", "players", "mobs", "animals", "teams",
                        "bot-check", "through-walls", "raycast"),
                h("轉頭", "min-turn", "max-turn", "turn-accel", "aim-drift", "flick-overshoot", "lazy", "short-stop"),
                h("出手", "swing-range", "max-per-tick", "require-press", "weapons-only", "inventory-check",
                        "ignored-backoff"));
        g("KillAura",
                h("常用", "Rotations", "CPS Mode", "MinCPS", "MaxCPS", "AttackRange", "SwingRange", "auto-block",
                        "Mode", "MoveFix"),
                h("目標", "Sort", "SwitchDelay", "FOV", "Players", "Bosses", "Mobs", "Animals", "Golems",
                        "Silverfish", "Teams", "BotCheck", "ThroughWalls"),
                h("出手", "ScanExtra", "Raycast", "ExitClick", "RequirePress",
                        "AllowMining", "WeaponsOnly", "AllowTools", "InventoryCheck"),
                h("格擋", "AttackTick", "LegitThreat", "LegitTolerance", "LegitSameTick", "AutoBlockRequirePress", "AutoBlockCPS", "AutoBlockRange"),
                h("轉頭", "SmoothBack", "AimMode", "MinTurnSpeed", "MaxTurnSpeed",
                        "Multipoint", "AimDrift", "FlickOvershoot", "TurnAccel", "Smoothing", "AngleStep", "AimLead", "AimLeadCap",
                        "RotationTiming", "LazyRotation", "ShortStop", "FailAim"),
                h("Hypixel 轉頭", "Hypixel*"),
                h("LiquidBounce 轉頭", "LB-*", "DeadZone", "MaxSpeed", "MinSpeed", "Acceleration", "Deceleration",
                        "Overshoot", "OverStr", "OverRecov", "Noise", "Randomize", "RandomRange", "YRandomize",
                        "VisualizeAim"),
                h("Advanced 轉頭", "Adv-*", "KBDisplace"),
                h("顯示與除錯", "ShowTarget", "Debug"));
        g("Clutch",
                h("觸發", "trigger", "hold-key", "edge-fall", "void-only", "safe-drop", "min-height", "combat-window",
                        "only-sideways", "fov"),
                h("放置", "reach", "place-interval", "safe-mode", "early-turn", "chain", "chain-length", "keep-y", "extra-block", "auto-ladder",
                        "swing", "pre-aim", "counter-knockback", "counter-delay"),
                h("轉頭", "mode", "move-fix", "speed", "max-speed", "rotation-random", "humanize", "ease",
                        "reset-angle", "snapback-speed", "snapback-delay", "snap-on-jump"),
                h("物品", "auto-switch", "switch-back", "item-spoof", "only-on-depletion", "pause-autoclicker"),
                h("其他", "disable-after", "log"));
        g("Scaffold",
                h("常用", "rotations", "sprint", "tower", "keep-y", "safe-walk", "move-fix", "turn-speed"),
                h("轉頭", "humanize", "telly-*"),
                h("移動", "ground-motion", "air-motion", "speed-motion", "eagle",
                        "edge-distance", "sneak-delay", "blocks-per-sneak"),
                h("疊高與高度", "hypixeltower", "keep-y-on-press", "no-keep-y-on-jump-potion"),
                h("放置", "pause-on-correction", "safe", "safe-delay-ticks", "multi-place", "swing", "item-spoof"),
                h("顯示", "block-counter", "outline-esp", "outline-color"));
        g("Humanization",
                h("預設與套用", "preset", "apply-*"),
                h("命中率", "hit-rate*", "drift-seconds", "window", "correction", "streaks", "hot-*", "cold-*"),
                h("情境修正", "distance-*", "strafe-penalty", "target-air-penalty", "self-air-penalty", "hurt-*",
                        "first-hit-bonus", "low-health-*", "min-hits-per-second", "max-hits-per-second"),
                h("怎麼揮空", "miss-*", "aim-off*"),
                h("反應時間", "reaction-*", "reacquire-window"),
                h("瞄準誤差", "tracking-noise", "noise-*", "overshoot*", "undershoot*", "lapse-*", "speed*"),
                h("點擊", "human-clicks", "click-*"),
                h("觀察", "show-stats", "log"));
        g("Velocity",
                h("模式與比例", "mode", "chance", "horizontal", "vertical", "explosions-horizontal",
                        "explosions-vertical"),
                h("Reduce 模式", "reduce", "attack-times", "only-sprinting", "reduce-when-can-attack", "slap-reduce"),
                h("Jump / Rotate", "jump", "rotate", "rotate-ticks", "tickExact"),
                h("Lag 模式", "lag-*"),
                h("Polar 模式", "polar-*"),
                h("依延遲調整（毫秒）", "500", "1000", "2000", "3000", "4000", "5000", "6000", "7000", "8000",
                        "9000", "10000"),
                h("其他", "fake-check", "debug-log"));
        g("HUD",
                h("顏色", "color", "color-*", "custom-color-*"),
                h("位置與大小", "position-*", "offset-*", "scale", "interface"),
                h("背景與特效", "background", "bg-alpha", "blur", "blur-radius", "glow", "glow-*", "bar",
                        "sidebar-mode", "shadow", "Shaders", "rounded", "corner-radius", "padding", "chat-outline"),
                h("模組清單", "suffixes", "separator-mode", "lower-case", "hide-*"),
                h("字型與資訊", "font-mode", "creida-*", "client-info", "blink-timer"),
                h("通知", "notifications", "toggle-sounds", "toggle-alerts"));
        g("NameTags",
                h("對象", "players", "friends", "enemies", "bosses", "mobs", "creepers", "endermen", "blazes",
                        "animals", "self", "bots", "show-invis", "show-self"),
                h("一般樣式", "mode", "scale", "auto-scale", "background", "shadow", "distance", "health", "armor",
                        "effects"),
                h("Raven 樣式", "raven-*", "bg-opacity", "bg-border", "only-name", "health-display",
                        "heart-symbol", "enchantments", "durability", "friend-color", "enemy-color"));
        g("AntiBot",
                h("基本檢查", "Basic", "MatrixBot", "Tab", "TabMode", "EntityID", "Color", "IllegalName",
                        "ExperimentalNPCDetection"),
                h("行為檢查", "LivingTime", "LivingTimeTicks", "Ground", "Air", "InvalidGround", "Swing", "Derp",
                        "WasInvisible", "Armor", "Ping", "NeedHit", "SpawnInCombat"),
                h("血量檢查", "Health", "MinHealth", "MaxHealth"),
                h("重複檢查", "DuplicateInWorld", "DuplicateInTab", "DuplicateCompareMode"),
                h("處理", "RemoveFromWorld", "Remove-Interval", "Debug"));
        g("Disabler",
                h("Grim / 通用", "grim-place", "start-sprint", "basic-disabler", "move-disabler",
                        "no-rotation-disabler", "modify-mode", "offset-amount", "only-combat"),
                h("取消封包", "cancel-*", "c03-no-move"),
                h("Vulcan", "vulcan-*"),
                h("Verus", "verus-*"),
                h("Intave / Matrix", "intave-*", "matrix-*", "ta-packet-counter"),
                h("Watchdog / 其他伺服器", "watchdog-*", "lifeboat", "spigot-spam", "message", "exp-void-tp*"),
                h("重送與除錯", "buffer-size", "repeat-times*", "flag-delay", "chat-debug"));
        g("TimerRange",
                h("模式與時鐘", "mode", "ticks", "timer-boost", "boost-delay-*", "timer-charged",
                        "charged-delay-*", "min-tick-delay", "max-tick-delay", "cooldown-tick"),
                h("距離", "range", "min-range", "max-range", "scan-range", "max-angle-difference"),
                h("預測", "blink", "predict-*"),
                h("限制", "on-web", "on-liquid", "on-forward-only", "reset-on-*"),
                h("顯示與除錯", "mark", "outline", "chat-debug"));
        g("BlockHit",
                h("一般", "Mode", "StopTicks", "Smart", "BlockHitChance", "Range"),
                h("自動格擋", "AutoBlockTime", "AutoMode", "AutoBlockRange", "HoldTick", "BlockDelay", "BlockTick",
                        "DelayPacketTick"),
                h("受傷時機", "MinHurtTime", "MaxHurtTime"),
                h("Manual", "ManualChance"),
                h("Predict", "Rhythm*"),
                h("Swing（舊）", "Predict*"),
                h("Slinky 格擋", "block-range", "max-hold-ticks", "require-*", "max-hurt-time"),
                h("Slinky 假動畫", "fake-animation*"),
                h("Slinky 延遲封包", "lag-*"));
        g("TargetHUD",
                h("樣式", "style", "Anim Mode", "animations", "head", "indicator", "outline", "shadow"),
                h("顏色", "color", "CustomColor", "Bg-Alpha", "background"),
                h("位置與大小", "position-*", "offset-*", "scale"),
                h("顯示條件", "ka-only", "chat-preview"));
        g("ESP2D",
                h("方框", "Outline", "Mode", "Local-Player", "Dropped-Items"),
                h("血量", "Health-Bar", "HBar-Mode", "Render-Absorption", "HealthNumber", "HP-Mode"),
                h("護甲", "Armor-Bar", "ABar-Mode", "ItemArmorNumber", "ArmorItems", "ArmorDurability"),
                h("名字", "Tags", "Tags-Background", "Item-Tags", "Font-Scale"),
                h("顏色", "Color", "Red", "Green", "Blue", "Saturation", "Brightness"));
        g("Blink",
                h("扣住", "direction", "mode", "hold-transactions", "auto-off", "timeout"),
                h("釋放", "auto-send", "send-threshold", "max-drift", "latency-budget", "ping-aware",
                        "release-on-correction", "release-per-tick", "release-every", "release-on-place",
                        "release-on-attack", "release-on-dig"),
                h("顯示", "announce-release", "server-position", "breadcrumbs"));
        g("FlagDetector",
                h("拉回偵測", "min-distance", "teleport-distance", "show-teleports", "group-bursts", "burst-window"),
                h("歸因", "context", "blame", "blame-window"),
                h("方塊與傷害", "detect-rejects", "place-log", "detect-low-damage", "damage-chat"),
                h("提示", "chat", "sound", "log-file"),
                h("HUD", "hud", "hud-*"));
        g("BedwarUtils",
                h("資訊 HUD", "hud", "hud-x", "hud-y", "hud-scale", "hud-shadow", "diamond-upgrades", "item-tracker",
                        "invis-alert", "auto-inc"),
                h("床追蹤 HUD", "bedtracker", "tracker-hud", "hud-position-*", "hud-offset-*", "tracker-hud-*"),
                h("提醒", "alerts*"),
                h("巨集", "macro*"));
        g("Xray",
                h("一般", "mode", "opacity", "range", "caves-only", "caves-radius"),
                h("礦物", "diamonds", "gold", "iron", "coal", "redstone", "lapis", "emeralds", "spawners", "canes",
                        "warts"),
                h("追蹤線", "*-tracers"));
        g("InvManager",
                h("時機", "min-delay", "max-delay", "open-delay"),
                h("整理", "auto-armor", "auto-armor-interval", "drop-trash", "check-durability", "blocks",
                        "projectiles", "arrow"),
                h("固定格子", "*-slot"));

        // ======================================================= SHARED HELP
        help("move-fix", "移動修正：靜默轉頭時讓移動方向跟伺服器看到的視角一致，避免移動類 flag。");
        help("movefix", "移動修正：靜默轉頭時讓移動方向跟伺服器看到的視角一致，避免移動類 flag。");
        help("swing", "動作時揮手（送出揮手動畫）。原版每次攻擊或放方塊都會揮手。");
        help("range", "作用距離（格）。");
        help("fov", "只對準心前方這個角度內的目標作用；360 為全方向。");
        help("teams", "略過隊友。");
        help("ignore-teammates", "略過隊友。");
        help("bot-check", "略過 AntiBot 判定為假人的玩家。");
        help("botcheck", "略過 AntiBot 判定為假人的玩家。");
        help("weapons-only", "只有手上拿武器時才作用。");
        help("weapon-only", "只有手上拿武器時才作用。");
        help("allow-tools", "拿斧頭、鎬子等工具時也算拿武器。");
        help("show-target", "在目標上畫標記。");
        help("min-cps", "每秒點擊次數下限（實際在上下限之間隨機）。");
        help("max-cps", "每秒點擊次數上限（實際在上下限之間隨機）。");
        help("min-delay", "最短延遲（隨機範圍下限）。");
        help("max-delay", "最長延遲（隨機範圍上限）。");
        help("open-delay", "打開畫面後先等這麼久才開始動作。");
        help("delay", "延遲。");
        help("chance", "觸發機率（%）。");
        help("mode", "運作模式。");
        help("speed", "速度。");
        help("rotations", "轉頭方式。");
        help("require-press", "只有按住對應按鍵時才作用。");
        help("chat", "在聊天欄顯示訊息。");
        help("sound", "出現時播放提示音。");
        help("log-file", "同時寫入 config/Atlas 底下的紀錄檔。");
        help("log", "寫入 config/Atlas 底下的紀錄檔。");
        help("debug", "輸出除錯訊息。");
        help("hud", "顯示 HUD 面板。");
        help("hud-x", "HUD 的水平位置。");
        help("hud-y", "HUD 的垂直位置。");
        help("x", "水平位置。");
        help("y", "垂直位置。");
        help("position-x", "水平位置。");
        help("position-y", "垂直位置。");
        help("offset-x", "水平微調。");
        help("offset-y", "垂直微調。");
        help("scale", "大小縮放。");
        help("color", "顏色。");
        help("opacity", "不透明度。");
        help("bg-alpha", "背景不透明度。");
        help("background-alpha", "背景不透明度。");
        help("background", "顯示背景。");
        help("shadow", "文字陰影。");
        help("outline", "外框。");
        help("players", "包含玩家。");
        help("friends", "包含好友。");
        help("enemies", "包含敵人。");
        help("self", "包含自己。");
        help("bots", "包含假人。");
        help("mobs", "包含怪物。");
        help("animals", "包含動物。");
        help("cooldown", "冷卻時間，觸發一次後要等這麼久才會再觸發。");
        help("item-spoof", "伺服器看到你換成方塊，但手上顯示的物品不變。");
        help("keep-y", "搭橋時維持在同一高度，不往上疊。");
        help("through-walls", "允許隔著方塊作用。");
        help("throughwalls", "允許隔著方塊作用。");
        help("alerts", "開啟提醒。");
        help("alerts-range", "提醒的距離。");
        help("alerts-sound", "提醒時播放音效。");
        help("macro", "觸發時自動送出設定的訊息。");
        help("macro-text", "巨集要送出的文字。");
        help("macro-delay", "巨集兩次送出之間的最短間隔。");
        help("dry-run", "只報告會做什麼，不真的動手（觀察用）。");
        help("ping-aware", "依你的延遲自動調整時間。");
        help("auto-switch", "需要時自動切到對應物品的格子。");
        help("switch-back", "用完切回原本的格子。");
        help("multi-place", "同一個 tick 最多放 4 格。Grim 會抓（MultiPlace），預設關。");

        // ======================================================= MODULE HELP
        String ka2 = "KillAura2";
        help(ka2, "legit-threat", "Legit 格擋什麼時候算「有危險」。Look（建議，參考 LiquidBounce NextGen）：有敵人的準心真的對著你（他的視線在攻擊距離內打到你、中間沒有牆）才舉劍，擋得少、衝刺斷得少。Swing：舊的判斷，你剛被打，或對方在 3.6 格內面向你（60 度內）在揮手；打架時幾乎一直成立。");
        help(ka2, "legit-tolerance", "Look 判斷時把你的身體放大幾格。越大越容易舉劍；0.3 是 NextGen 的預設。");
        help(ka2, "block-min-ticks", "劍舉起後至少維持幾 tick，放下後至少隔 2 tick 才能再舉（Rise 的反作弊會抓 1-2 tick 就切換的格擋）。");
        help(ka2, "block-stop-sprint", "舉劍前一 tick 先停疾跑、舉劍時不疾跑，放下後等疾跑恢復才出手，讓那一下是疾跑攻擊（邊疾跑邊擋是不可能的狀態，會被抓）。");
        help(ka2, "block-release", "怎麼放下劍。Slot：這 tick 切到下一格、下一 tick 切回來（1.8 伺服器要中間隔一個移動封包才會放開）。Release：一般的放開封包。");
        help(ka2, "block-log", "每次被打寫一行到 config/Atlas/ka2block-*.txt：用扣血量判斷伺服器有沒有算到格擋，加上被打前 13 tick 的舉劍和疾跑狀態。");
        help(ka2, "mode", "Single 一直打同一個人，直到他失效或離開攻擊範圍而有別人在範圍內；Switch 每過 switch-delay 換下一個範圍內的人。");
        help(ka2, "sort", "選目標的順序（攻擊範圍內的人永遠優先）：距離、血量、離準心的角度、受傷時間。");
        help(ka2, "switch-delay", "Switch 模式換人的間隔（毫秒）。");
        help(ka2, "attack-range", "出手距離（眼睛到碰撞箱）。視角射線要在這個距離內打到對方才會出手；原版是 3.0。");
        help(ka2, "scan-extra", "比出手距離多看幾格：對方還沒進入攻擊範圍就先開始轉頭。");
        help(ka2, "fov", "只選在準心這個角度以內的人。");
        help(ka2, "through-walls", "隔著方塊也選、也打。關掉時視線被擋就不出手。");
        help(ka2, "players", "攻擊玩家。");
        help(ka2, "mobs", "攻擊怪物（含鐵傀儡、Boss）。");
        help(ka2, "animals", "攻擊動物。");
        help(ka2, "teams", "不打同隊的人。");
        help(ka2, "bot-check", "不打判定為機器人的玩家。");
        help(ka2, "min-cps", "每秒點擊下限。每輪連擊在上下限間抽一個速度，間隔像真人一樣不規則。");
        help(ka2, "max-cps", "每秒點擊上限。");
        help(ka2, "max-per-tick", "一個 tick 最多出手幾次。1 最安全；2 和原版一樣允許快速連點擠在同一 tick。");
        help(ka2, "min-turn", "接近目標時每 tick 最少轉幾度（舊版 MinTurnSpeed，你調好的值是 14）。");
        help(ka2, "max-turn", "離目標 60 度以上時每 tick 最多轉幾度（舊版 MaxTurnSpeed，60）。中間依平滑曲線變化。");
        help(ka2, "turn-accel", "轉速每 tick 最多能增加多少（舊版 TurnAccel，30）；0 為不限制。");
        help(ka2, "aim-drift", "瞄準點在碰撞箱裡慢慢飄移的比重（舊版 AimDrift，0.4），不會一直瞄同一點。");
        help(ka2, "flick-overshoot", "大角度轉頭時偶爾轉過頭一點再拉回來，像真人甩槍。");
        help(ka2, "short-stop", "偶爾停頓 1～2 tick，只轉一小段，像真人的猶豫。");
        help(ka2, "lazy", "準心已經能打到時就不轉頭，像真人一樣手不亂動。");
        help(ka2, "move-fix", "FollowAim（建議）：移動跟著瞄準方向轉，按鍵不變，伺服器一定能模擬，最不容易被拉回。KeepDirection：重新對應按鍵，維持往鏡頭方向走；對應後不是往前時會取消疾跑。None：不處理。");
        help(ka2, "require-press", "按住左鍵時才攻擊。");
        help(ka2, "weapons-only", "只有拿劍時才攻擊。");
        help(ka2, "inventory-check", "開著箱子或背包時不攻擊。");
        help(ka2, "ignored-backoff", "連續 3 下打出去對方都沒反應（大廳、出生保護、伺服器不收）時，暫停攻擊他 1.5 秒，只瞄不打。等反應的時間依延遲算（延遲未知時當 300ms），不會因為延遲高就誤判。");
        help(ka2, "swing-range", "點擊打不到時（視角還沒對上、對方剛好在攻擊範圍外），對方在這個距離內就照樣揮空，像真人邊靠近邊點；格擋也只在這個距離內舉劍。");
        help(ka2, "raycast", "照原版準心：視線上第一個碰到的實體才吃這一下。前面擋著另一個敵人就改打他；擋著的是隊友等其他東西就不出手。");
        help(ka2, "auto-block", "Legit（建議，舊版 LEGIT 的做法）：打中後 1～2 tick（大約五次裡四次），對方還在無敵時間、而且他打得到你（你剛被打，或他面向你在揮）時舉劍，對方無敵時間結束前 1～2 tick 放下，下一下照常打中。Always：不看威脅，只要對方在無敵時間內就舉劍。None：不格擋。Clutch 墊方塊或正在放方塊時不舉劍。出手時機看 InvulnTiming 模組（hold-fire 開著時，對方無敵時間內不點擊，結束後下一次點擊才出手）。");
        String ka = "KillAura";
        help(ka, "Mode", "Single 一直打同一個人；Switch 每過 SwitchDelay 換下一個目標。");
        help(ka, "Sort", "選目標的優先順序：距離、血量、受傷時間、或離準心最近。");
        help(ka, "SwitchDelay", "多久重新挑一次目標（毫秒）；Switch 模式下也是換人的間隔。");
        help(ka, "CPS Mode", "Normal：在 MinCPS～MaxCPS 間隨機。Record：照一段真人錄下的點擊節奏（約 9.8 CPS）。Human：真人點擊器，每輪連擊在 MinCPS～MaxCPS 間抽一個速度，間隔呈對數常態分布。");
        help(ka, "MinCPS", "每秒攻擊次數下限（實際在上下限間隨機，最高受 tick 限制為 20）。");
        help(ka, "MaxCPS", "每秒攻擊次數上限。");
        help(ka, "AttackRange", "真正出手的距離（格）。原版是 3.0。");
        help(ka, "SwingRange", "開始轉頭與揮手的距離，略大於攻擊距離。");
        help(ka, "RotationTiming", "Normal：一直瞄準。Snap：只在「轉到位時剛好要點」才轉頭。");
        help(ka, "LazyRotation", "視角已經打得到目標就不再轉。");
        help(ka, "ShortStop", "偶爾（每 tick 3%）停頓 1–2 tick，只轉一點點，像真人。");
        help(ka, "FailAim", "偶爾（每 tick 3%）瞄偏 5–10 度 1–4 tick，像真人失誤。");
        help(ka, "Raycast", "Enemy：視角先碰到別的敵人，就打那個人並改成目標（像真的準星）。None：永遠打選定的目標。");
        help(ka, "ExitClick", "目標下一 tick 就要離開攻擊距離時，把下一下提早一 tick 打出去。");
        help(ka, "ScanExtra", "比揮手距離再遠幾格就先轉頭（不揮手），每個目標隨機 ±0.5；0 = 只從揮手距離才轉。");
        help(ka, "RequirePress", "只有按住左鍵時才攻擊。");
        help(ka, "AllowMining", "準心對著方塊且按住左鍵時，讓你挖方塊而不是打人。");
        help(ka, "InventoryCheck", "開著背包、箱子等畫面時不攻擊。");
        help(ka, "yuri-only-when-hurt", "Yuri 模式：自己的 hurtTime 到 yuri-hurt-ticks 才格擋。");
        help(ka, "yuri-hurt-ticks", "Yuri 模式：hurtTime 到多少才允許格擋。");
        help(ka, "simulate-clicks", "Yuri：每一下都用原版左鍵點擊，打準心對著的東西，而不是 aura 自己送攻擊封包。打空會有原版的點擊冷卻，除非開著 NoDelay。");
        help(ka, "engine", "LiquidBounce：完整移植的 LiquidBounce NextGen KillAura（設定在 LiquidBounce 標題下）。Atlas：原本的 Silent Aura，所有模式和設定照舊。");
        help("LiquidAura", "AngleSmooth", "每 tick 怎麼轉：Linear、Sigmoid、Interpolation、Acceleration，或 AimAssist（用 Atlas AimAssist 的 Smooth 轉法和瞄準點，數值讀 AimAssist 模組自己的速度、smoothing、lead、multipoint；AimAssist 本身可以關著）。");
        help("LiquidAura", "RangeIncrease", "攻擊距離 = 3.0 + 這個值。LB 預設 1.0 也就是 4 格，會檢查距離的伺服器請調低。");
        help("LiquidAura", "MovementCorrection", "Off：照鏡頭走。Strict：照伺服器朝向走。Silent：連按鍵方向一起轉。ChangeLook：鏡頭跟著瞄準轉。");
        help("LiquidAura", "AutoBlocking", "LB 的自動格擋：兩下之間舉劍，打之前放下。");
        help("LiquidAura", "FightBot", "自動走位：走到目標視線外的側邊，還沒到點擊時就後退。");
        help(ka, "auto-block", "自動格擋方式。Yuri-Hypixel：3 格內兩下之間舉劍一 tick，8 CPS。Yuri-Legit：自己 hurtTime 6-10 且目標 3 格內時按住右鍵，5 CPS。Yuri-NCP：每 tick 先放下、移動封包後再舉。Yuri-Vanilla：每 tick 送格擋封包。LEGIT：只在對方面向你、正在揮劍（或你剛被打）時才 block hit——打完立刻舉劍、下一下之前放下，不降 CPS；對方沒威脅就不格擋，跑得快。SameTick：要點的那 tick 先放下、打、立刻再舉，其餘時間舉著；離開揮手距離就放下。");
        help(ka, "LegitThreat", "LEGIT 什麼時候算「有危險」才舉劍。Look（建議，參考 LiquidBounce NextGen）：敵人的準心真的對著你（他的視線在攻擊距離內打到你、中間沒有牆）才擋，擋得少、跑得快。Swing：舊的判斷，你剛被打，或敵人在 3.6 格內面向你（60 度內）在揮手；打架時幾乎一直成立。");
        help(ka, "LegitTolerance", "Look 判斷時把你的身體放大幾格。越大越早舉劍、擋得越多；0.3 是 NextGen 的預設。");
        help(ka, "LegitSameTick", "開：要出手的那個 tick 先放下、打、立刻再舉（同 SameTick），不浪費 tick。關：舊做法，出手前一個 tick 先放下，每下多花一個 tick。部分反作弊可能在意放下和出手在同一個 tick，被抓再關掉。");
        help(ka, "AutoBlockCPS", "格擋時的攻擊速度（LEGIT、SameTick 不用，照 MinCPS～MaxCPS）。");
        help(ka, "AutoBlockRange", "目標在這個距離內就舉劍格擋。");
        help(ka, "AutoBlockRequirePress", "只有按住右鍵時才自動格擋。");
        help(ka, "AttackTick", "SWAP 格擋時，NoSlow 的 No Attack 在哪個 tick 不出手。");
        help(ka, "Rotations", "Silent：伺服器看到你轉頭，畫面不動。Legit：同時轉畫面。LockView：鎖定視角。");
        help(ka, "MoveFix", "靜默轉頭時修正移動方向，避免移動類 flag。");
        help(ka, "SmoothBack", "沒目標或關閉時，把伺服器看到的視角平滑轉回你的畫面，不會一瞬間跳回去。");
        help(ka, "AimMode", "SMOOTHSTEP：遠快近慢、保證到位。LEGACY：舊的比例平滑。");
        help(ka, "MinTurnSpeed", "快對準時每 tick 最少轉幾度。");
        help(ka, "MaxTurnSpeed", "離目標很遠時每 tick 最多轉幾度（平滑轉回也用這個速度）。");
        help(ka, "Multipoint", "瞄準碰撞箱上離準心最近的點，而不是中心，轉頭量最小。");
        help(ka, "TurnAccel", "轉頭速度每 tick 最多能加快幾度，像手一樣慢慢加速。");
        help(ka, "AimDrift", "瞄準點在碰撞箱裡慢慢飄動的程度。0 = 永遠瞄最近的邊緣；越高越往箱子裡面一個會慢慢移動的點瞄，不會一直鎖同一點。");
        help(ka, "FlickOvershoot", "大幅度轉頭時偶爾會稍微轉過頭（最多 4 度），再修正回來，像真人甩槍。");
        help(ka, "Smoothing", "LEGACY 模式的平滑程度。");
        help(ka, "AngleStep", "LEGACY 模式每 tick 最多轉幾度。");
        help(ka, "AimLead", "依延遲往目標移動方向多瞄一點（0 = 不預判；Grim 建議 0）。");
        help(ka, "AimLeadCap", "預判最多偏移幾格。");
        help(ka, "FOV", "只攻擊準心前方這個角度內的目標。");
        help(ka, "ThroughWalls", "允許攻擊隔著方塊的目標。");
        help(ka, "KBDisplace", "Advanced 專用：把擊退方向轉向危險處。SAFE 只在還打得到時轉。");
        help(ka, "ShowTarget", "在目標身上畫方框。");
        help(ka, "Debug", "Health：在聊天欄顯示自己每次血量變化。");

        String cl = "Clutch";
        help(cl, "trigger", "AUTO：偵測到會摔下去就自動接。HOLD：按住 hold-key 時才作用。");
        help(cl, "hold-key", "trigger 為 HOLD 時要按住的按鍵。");
        help(cl, "edge-fall", "沒被打時，往虛空掉多少格才出手（小跳不算掉落）。");
        help(cl, "void-only", "只在下面是虛空時才接。");
        help(cl, "safe-drop", "落差在這個高度以內、下面又有地面時就不接。");
        help(cl, "combat-window", "被打後多少毫秒內的掉落算「被擊落」。");
        help(cl, "reach", "放方塊的最遠距離。");
        help(cl, "place-interval", "兩次放置之間至少隔幾個 tick。");
        help(cl, "safe-mode", "安全模式：先用伺服器已經知道的視角點；大角度轉頭在時間夠時提早一個 tick 轉。不降連續放置速度。關閉 = 原本同一 tick 轉頭加點擊。");
        help(cl, "early-turn", "要轉超過這個角度、而且時間還來得及時，提早一個 tick 先轉頭，下一個 tick 再點（伺服器先看到視角）。來不及就照原本同一 tick 轉加點，不會變慢。180 = 關閉。");
        help(cl, "chain", "直接接不到時，先鋪一條橋延伸過去再接。");
        help(cl, "chain-length", "橋最多鋪幾格。");
        help(cl, "keep-y", "不會放到你起跳前站的那一層以上。");
        help(cl, "extra-block", "快落地且會踩在方塊邊緣時，在腳下正中央多補一格。");
        help(cl, "humanize", "點擊位置用常態分佈隨機，轉頭走 Rotations 引擎的速度隨機與弧線。");
        help(cl, "ease", "轉頭最後一段減速（會多花 tick，接不到的機率稍增）。");
        help(cl, "rotation-random", "隨機程度（點擊位置的分散範圍）。");
        help(cl, "speed", "每 tick 最少轉幾度。");
        help(cl, "max-speed", "每 tick 最多轉幾度。");
        help("Scaffold", "turn-speed", "每 tick 最多轉幾度（DEFAULT～Hypixel 模式）。不再一次甩到位，轉不到就等下一 tick，不會亂點。180 = 不限制。");
        help("Scaffold", "humanize", "轉頭加上 Rotations 引擎的速度隨機與弧線，比較像人手。");
        help("Scaffold", "telly-jump", "TELLY 模式：每次起跳時先面向前方跳，再分幾個 tick 轉回身後放方塊（telly 搭橋）；轉身時不點擊。");
        help("Scaffold", "pause-on-correction", "被伺服器拉回後，等一個來回延遲再放方塊；被退回的方塊也會暫停。避免在錯的位置連續放出 ghost block。");
        help(cl, "pre-aim", "還沒到時機前先把頭轉到大概的位置，等一下轉比較少。");
        help(cl, "counter-knockback", "被打飛時往回走，抵銷一部分擊退。");
        help(cl, "auto-ladder", "接不到方塊時嘗試放梯子。");
        help(cl, "only-on-depletion", "只有手上方塊用完時才自動換格子。");
        help(cl, "pause-autoclicker", "接的時候暫停 AutoClicker。");
        help(cl, "disable-after", "接完一次就自動關閉 Clutch。");

        String rt = "Rotations";
        help(rt, "speed-noise", "轉頭速度上下浮動的幅度（常態分佈的標準差，% 為每步的比例）。");
        help(rt, "curve", "轉頭路徑偏離直線的幅度（每步的 %）。0 = 直線。");
        help(rt, "curve-smooth", "弧線的平滑度：越高，每 tick 的偏移越延續上一 tick。");
        help(rt, "ease", "接近目標時減速，不會全速衝到再急停。");
        help(rt, "ease-zone", "剩下幾度以內開始減速。");
        help(rt, "ease-floor", "減速時每 tick 最少還轉幾度，保證一定轉得到。");

        String pt = "PlayTracker";
        help(pt, "show-today", "同時顯示今天累計的時間與場數。");
        help(pt, "show-wins", "顯示勝敗場數。");
        help(pt, "show-mitigation", "偵測到被減傷或連續打不到時，多顯示一行提醒（需要 FlagDetector）。");
        help(pt, "mitigation-always", "沒有減傷時也顯示「減傷 無」。");

        String fd = "FlagDetector";
        help(fd, "detect-rejects", "方塊被伺服器退回時回報，並直接算給放那格的模組。");
        help(fd, "detect-low-damage", "偵測打中但傷害偏低（減傷），以及連續打不到。");
        help(fd, "damage-chat", "每一刀都在聊天欄顯示實際傷害與原版最低應有的傷害。");
        help(fd, "blame", "顯示是哪個模組最可能造成這次 flag。");
        help(fd, "blame-window", "歸因時往前看幾秒內的模組動作。");
        help(fd, "min-distance", "拉回小於這個距離就不回報。");
        help(fd, "group-bursts", "短時間內連續的拉回合併成一行。");
    }
}
