package atlas.nickresolver;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NickSettings {
    private static final String GENERAL = "general";
    private static final String CLOUD = "cloud";

    public enum NametagMode {
        ABOVE,

        INLINE
    }

    private final Configuration config;

    private final Property pEnabled;
    private final Property pShowNametag;
    private final Property pNametagMode;
    private final Property pShowChat;
    private final Property pShowUnresolved;
    private final Property pShowProbable;
    private final Property pShowSkinOwner;
    private final Property pUseMojang;
    private final Property pInspectPayloads;
    private final Property pCacheTtlMinutes;
    private final Property pMaxRequestsPer10Min;
    private final Property pCloudEnabled;
    private final Property pCloudReport;
    private final Property pCloudLookup;
    private final Property pCloudUrl;
    private final Property pCloudKey;
    private final Property pCloudAutoKey;
    private final Property pSessionLog;
    private final Property pShowReal;

    public volatile boolean enabled;
    public volatile boolean showNametag;
    public volatile NametagMode nametagMode;
    public volatile boolean showChat;
    public volatile boolean showUnresolved;
    public volatile boolean showProbable;
    public volatile boolean showSkinOwner;
    public volatile boolean useMojang;
    public volatile boolean inspectPayloads;
    public volatile int cacheTtlMinutes;
    public volatile int maxRequestsPer10Min;
    public volatile boolean cloudEnabled;
    public volatile boolean cloudReport;
    public volatile boolean cloudLookup;
    public volatile String cloudUrl;
    public volatile String cloudKey;
    public volatile boolean cloudAutoKey;
    public volatile boolean sessionLog;
    public volatile boolean showReal;

    private final Map<String, Property> toggles = new LinkedHashMap<String, Property>();
    private final Map<String, String> labels = new LinkedHashMap<String, String>();

    public NickSettings(File file) {
        this.config = new Configuration(file);
        this.config.load();

        this.pEnabled = this.config.get(GENERAL, "enabled", true, "啟用 Denick（解析暱稱玩家的真實帳號）");
        this.pShowNametag = this.config.get(GENERAL, "showNametag", true, "在頭頂名牌顯示真名");
        this.pNametagMode = this.config.get(GENERAL, "nametagMode", "ABOVE",
                "名牌顯示方式：ABOVE（名牌上方另起一行）或 INLINE（暱稱 [真名]）",
                new String[]{"ABOVE", "INLINE"});
        this.pShowChat = this.config.get(GENERAL, "showChat", true, "聊天訊息前加上 [真名]");
        this.pShowUnresolved = this.config.get(GENERAL, "showUnresolved", true, "標記已確認使用暱稱但查不到真名的玩家");
        this.pShowProbable = this.config.get(GENERAL, "showProbable", true, "顯示推測結果（皮膚紀錄、伺服器訊息），以黃色與問號標示");
        this.pShowSkinOwner = this.config.get(GENERAL, "showSkinOwner", false, "顯示借用皮膚的原主帳號");
        this.pUseMojang = this.config.get(GENERAL, "useMojangApi", true, "向 Mojang API 查詢 UUID 對應的帳號名稱");
        this.pInspectPayloads = this.config.get(GENERAL, "inspectPayloads", true, "解析伺服器自訂封包中的身分資料");
        this.pCacheTtlMinutes = this.config.get(GENERAL, "cacheTtlMinutes", 30, "Mojang 查詢結果快取時間（分鐘）", 1, 1440);
        this.pMaxRequestsPer10Min = this.config.get(GENERAL, "maxRequestsPer10Min", 500,
                "每 10 分鐘最多 Mojang 請求數（Mojang 上限約 600）", 10, 600);
        this.pCloudEnabled = this.config.get(CLOUD, "enabled", false, "連線到共享 Denick 資料庫（Cloudflare Worker）");
        this.pCloudReport = this.config.get(CLOUD, "report", true, "回報觀察到的玩家資料（皮膚雜湊、UUID、名稱）");
        this.pCloudLookup = this.config.get(CLOUD, "lookup", true, "本地查不到真名時查詢雲端資料庫");
        this.pCloudUrl = this.config.get(CLOUD, "url", "https://atlas-denick.atlas-client.workers.dev", "Worker 網址");
        this.pCloudKey = this.config.get(CLOUD, "apiKey", "", "API 金鑰（X-API-Key）");
        this.pCloudAutoKey = this.config.get(CLOUD, "autoKey", true, "沒有金鑰時自動向伺服器申請（需伺服器開放註冊）");

        this.pSessionLog = this.config.get(GENERAL, "sessionLog", true,
                "每次連線寫一份測試紀錄到 logs/（只存在本機，不上傳）");
        this.pShowReal = this.config.get(GENERAL, "showReal", false, "名牌標示已確認是本名的玩家 [REAL]");
        this.toggle("enabled", this.pEnabled, "暱稱解析");
        this.toggle("nametag", this.pShowNametag, "名牌顯示真名");
        this.toggle("chat", this.pShowChat, "聊天顯示真名");
        this.toggle("unresolved", this.pShowUnresolved, "標記未知暱稱");
        this.toggle("probable", this.pShowProbable, "顯示推測結果");
        this.toggle("skinowner", this.pShowSkinOwner, "顯示皮膚原主");
        this.toggle("mojang", this.pUseMojang, "Mojang 查詢");
        this.toggle("payloads", this.pInspectPayloads, "解析伺服器封包");
        this.toggle("cloud", this.pCloudEnabled, "雲端資料庫");
        this.toggle("cloudreport", this.pCloudReport, "回報到雲端");
        this.toggle("cloudlookup", this.pCloudLookup, "查詢雲端");
        this.toggle("log", this.pSessionLog, "測試紀錄");
        this.toggle("real", this.pShowReal, "標示本名 [REAL]");

        this.reloadCached();
        if (this.config.hasChanged()) {
            this.config.save();
        }
    }

    private void toggle(String key, Property property, String label) {
        this.toggles.put(key, property);
        this.labels.put(key, label);
    }

    private void reloadCached() {
        this.enabled = this.pEnabled.getBoolean();
        this.showNametag = this.pShowNametag.getBoolean();
        NametagMode mode;
        try {
            mode = NametagMode.valueOf(this.pNametagMode.getString().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            mode = NametagMode.ABOVE;
        }
        this.nametagMode = mode;
        this.showChat = this.pShowChat.getBoolean();
        this.showUnresolved = this.pShowUnresolved.getBoolean();
        this.showProbable = this.pShowProbable.getBoolean();
        this.showSkinOwner = this.pShowSkinOwner.getBoolean();
        this.useMojang = this.pUseMojang.getBoolean();
        this.inspectPayloads = this.pInspectPayloads.getBoolean();
        this.cacheTtlMinutes = clamp(this.pCacheTtlMinutes.getInt(), 1, 1440);
        this.maxRequestsPer10Min = clamp(this.pMaxRequestsPer10Min.getInt(), 10, 600);
        this.cloudEnabled = this.pCloudEnabled.getBoolean();
        this.cloudReport = this.pCloudReport.getBoolean();
        this.cloudLookup = this.pCloudLookup.getBoolean();
        this.cloudUrl = this.pCloudUrl.getString().trim();
        this.cloudKey = this.pCloudKey.getString().trim();
        this.cloudAutoKey = this.pCloudAutoKey.getBoolean();
        this.sessionLog = this.pSessionLog.getBoolean();
        this.showReal = this.pShowReal.getBoolean();
    }

    public synchronized void save() {
        this.config.save();
    }

    public Iterable<String> toggleKeys() {
        return this.toggles.keySet();
    }

    public String label(String key) {
        String label = this.labels.get(key);
        return label != null ? label : key;
    }

    public synchronized boolean getToggle(String key) {
        Property p = this.toggles.get(key);
        return p != null && p.getBoolean();
    }

    public synchronized boolean setToggle(String key, boolean value) {
        Property p = this.toggles.get(key);
        if (p == null) {
            return false;
        }
        p.set(value);
        this.reloadCached();
        this.config.save();
        return true;
    }

    public synchronized void setNametagMode(NametagMode mode) {
        this.pNametagMode.set(mode.name());
        this.reloadCached();
        this.config.save();
    }

    public synchronized void setCloudEndpoint(String url, String key) {
        if (url != null) {
            this.pCloudUrl.set(url);
        }
        if (key != null) {
            this.pCloudKey.set(key);
        }
        this.reloadCached();
        this.config.save();
    }

    public long cacheTtlMillis() {
        return this.cacheTtlMinutes * 60_000L;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
