package atlas.module.modules;

import atlas.util.Ping;
import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.Property;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.IntProperty;
import atlas.util.ChatUtil;
import atlas.util.LatencyTiers;
import net.minecraft.client.Minecraft;

public class LatencyGovernor extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String OWNER = "LatencyGovernor";

    public final IntProperty midPing = new IntProperty("mid-ping", 150, 50, 500);
    public final IntProperty highPing = new IntProperty("high-ping", 250, 80, 800);

    public final IntProperty jitterLimit = new IntProperty("jitter", 60, 10, 400);

    public final IntProperty hysteresis = new IntProperty("hysteresis", 20, 0, 150);

    public final IntProperty settle = new IntProperty("settle-seconds", 10, 1, 30);

    public final BooleanProperty governBacktrack = new BooleanProperty("govern-backtrack", true);
    public final BooleanProperty governReach = new BooleanProperty("govern-reach", true);

    public final BooleanProperty cutSendSideLag = new BooleanProperty("cut-send-lag", true);
    public final BooleanProperty chat = new BooleanProperty("chat", true);

    private static final int SAMPLES = 40;
    private final int[] pings = new int[SAMPLES];
    private int pingIndex;
    private int pingFilled;

    private final LatencyTiers tiers = new LatencyTiers();

    private int tier = -1;
    private int timer;

    private final java.util.Set<Class<?>> cut = new java.util.LinkedHashSet<Class<?>>();

    public LatencyGovernor() {
        super("LatencyGovernor", false, false,
                "Scales latency-sensitive settings down when the connection degrades");

        atlas.management.Shutdown.register(atlas.management.Shutdown.Stage.RESTORE, "LatencyGovernor.restore",
                this::restoreForExit);
    }

    private void restoreForExit() {
        if (!this.isEnabled()) {
            return;
        }
        releaseAll();
        giveBack();
    }

    private void giveBack() {
        for (Class<?> type : this.cut) {
            Module module = Atlas.moduleManager.modules.get(type);
            if (module != null && !module.isEnabled()) {
                try {
                    module.setEnabled(true);
                } catch (Exception ignored) {
                }
            }
        }
        this.cut.clear();
    }

    private void resetSamples() {
        this.pingIndex = 0;
        this.pingFilled = 0;
    }

    @Override
    public void onEnabled() {
        resetSamples();
        this.tiers.reset();
        this.tier = -1;
        this.timer = 0;
    }

    @Override
    public void onDisabled() {
        releaseAll();
        this.tiers.reset();
        this.tier = -1;
        giveBack();
    }

    private int ping() {
        return Ping.own();
    }

    @EventTarget
    public void onSession(atlas.events.SessionEvent event) {
        if (event.getType() == atlas.events.SessionEvent.Type.START && event.serverChanged()) {
            resetSamples();
            releaseAll();
            this.tiers.reset();
            this.tier = -1;
        } else if (event.getType() == atlas.events.SessionEvent.Type.END) {
            releaseAll();
            giveBack();
            this.tiers.reset();
            this.tier = -1;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (++this.timer < 20) {
            return;
        }
        this.timer = 0;

        int ping = ping();
        if (ping > 0) {
            this.pings[this.pingIndex] = ping;
            this.pingIndex = (this.pingIndex + 1) % SAMPLES;
            if (this.pingFilled < SAMPLES) {
                this.pingFilled++;
            }
        }

        if (this.pingFilled < 10) {
            return;
        }

        int average = average();
        int jitter = meanDeviation(average);
        int next = this.tiers.update(average, jitter, this.midPing.getValue(), this.highPing.getValue(),
                this.jitterLimit.getValue(), this.hysteresis.getValue(), this.settle.getValue());
        if (next == this.tier) {
            if (this.tier > 0) {
                applyOverrides();
            }
            return;
        }
        int previous = this.tier;
        this.tier = next;
        applyOverrides();
        if (next >= 2 && previous < 2 && this.cutSendSideLag.getValue()) {
            disable(LagRange.class, "LagRange");
            disable(Blink.class, "Blink");
            disable(ServerLag.class, "ServerLag");
            disable(FakeLag.class, "FakeLag");
        }
        if (this.chat.getValue()) {
            ChatUtil.sendFormatted(String.format(
                    "&7[&bLatencyGovernor&7] &f%s&7 &8(&fping %dms&7, jitter &f%dms&8)",
                    tierName(next), average, jitter));
        }
    }

    private int average() {
        int total = 0;
        for (int i = 0; i < this.pingFilled; i++) {
            total += this.pings[i];
        }
        return total / this.pingFilled;
    }

    private int meanDeviation(int average) {
        int total = 0;
        for (int i = 0; i < this.pingFilled; i++) {
            total += Math.abs(this.pings[i] - average);
        }
        return total / this.pingFilled;
    }

    private static String tierName(int tier) {
        return tier == 0 ? "clear" : (tier == 1 ? "holding back" : "minimum");
    }

    private void applyOverrides() {
        Module backtrackModule = Atlas.moduleManager.modules.get(BackTrack.class);
        if (backtrackModule instanceof BackTrack) {
            BackTrack backtrack = (BackTrack) backtrackModule;
            if (this.tier > 0 && this.governBacktrack.getValue()) {
                float scale = this.tier == 1 ? 0.6F : 0.4F;
                govern(backtrack.normalDelay, scale);
                govern(backtrack.adaptiveDelay, scale);
            } else {
                backtrack.normalDelay.release(OWNER);
                backtrack.adaptiveDelay.release(OWNER);
            }
        }
        Module reachModule = Atlas.moduleManager.modules.get(Reach.class);
        if (reachModule instanceof Reach) {
            Reach reach = (Reach) reachModule;
            if (this.tier > 0 && this.governReach.getValue()) {
                float cap = this.tier == 1 ? 3.05F : 3.0F;
                if (reach.range.getBaseValue() > cap) {
                    set(reach.range, cap);
                } else {
                    reach.range.release(OWNER);
                }
            } else {
                reach.range.release(OWNER);
            }
        }
    }

    private void govern(IntProperty property, float scale) {
        int base = property.getBaseValue();
        int target = Math.min(base, Math.max(50, Math.round(base * scale)));
        if (target < base) {
            set(property, target);
        } else {
            property.release(OWNER);
        }
    }

    private static void set(Property<?> property, Object value) {
        if (!value.equals(property.overrideOf(OWNER))) {
            property.override(Property.Source.GOVERNOR, OWNER, value);
        }
    }

    private void releaseAll() {
        Module backtrackModule = Atlas.moduleManager.modules.get(BackTrack.class);
        if (backtrackModule instanceof BackTrack) {
            ((BackTrack) backtrackModule).normalDelay.release(OWNER);
            ((BackTrack) backtrackModule).adaptiveDelay.release(OWNER);
        }
        Module reachModule = Atlas.moduleManager.modules.get(Reach.class);
        if (reachModule instanceof Reach) {
            ((Reach) reachModule).range.release(OWNER);
        }
    }

    private void disable(Class<?> type, String label) {
        Module module = Atlas.moduleManager.modules.get(type);
        if (module != null && module.isEnabled()) {
            module.setEnabled(false);
            this.cut.add(type);
            if (this.chat.getValue()) {
                ChatUtil.sendFormatted("&7[&bLatencyGovernor&7] &cdisabled &f" + label
                        + "&7 for this connection");
            }
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.tier < 0 ? "sampling" : tierName(this.tier)};
    }
}
