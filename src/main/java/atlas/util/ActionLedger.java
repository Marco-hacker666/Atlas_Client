package atlas.util;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C13PacketPlayerAbilities;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ActionLedger {
    private static final long RETENTION_MS = 15000L;
    private static final int CAP = 512;

    private static final ArrayDeque<Record> ENTRIES = new ArrayDeque<Record>();
    private static final Object LOCK = new Object();

    private ActionLedger() {
    }

    public static final class Record {
        public final long at;
        public final String module;
        public final String kind;
        public final int count;

        Record(String module, String kind, int count) {
            this.at = System.currentTimeMillis();
            this.module = module;
            this.kind = kind;
            this.count = count;
        }
    }

    public static void note(String module, String kind) {
        note(module, kind, 1);
    }

    public static void note(String module, String kind, int count) {
        if (module == null || count <= 0) {
            return;
        }
        synchronized (LOCK) {
            ENTRIES.addLast(new Record(module, kind, count));
            long cutoff = System.currentTimeMillis() - RETENTION_MS;
            while (!ENTRIES.isEmpty()
                    && (ENTRIES.size() > CAP || ENTRIES.peekFirst().at < cutoff)) {
                ENTRIES.removeFirst();
            }
        }
    }

    public static String callerModule() {
        return callerModuleExcept(null);
    }

    public static String callerModuleExcept(String self) {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (StackTraceElement frame : stack) {
            String name = frame.getClassName();
            if (!name.startsWith("atlas.module.modules.")) {
                continue;
            }
            String simple = name.substring("atlas.module.modules.".length());

            int inner = simple.indexOf('$');
            String module = inner < 0 ? simple : simple.substring(0, inner);
            if (module.equals(self)) {
                continue;
            }
            return module;
        }
        return null;
    }

    public static boolean isAttributable(Packet<?> packet) {
        return packet instanceof C02PacketUseEntity
                || packet instanceof C07PacketPlayerDigging
                || packet instanceof C08PacketPlayerBlockPlacement
                || packet instanceof C0APacketAnimation
                || packet instanceof C0BPacketEntityAction
                || packet instanceof C13PacketPlayerAbilities;
    }

    public static boolean cancelMatters(Packet<?> packet) {
        String name = packet.getClass().getSimpleName();
        for (String cosmetic : COSMETIC) {
            if (name.startsWith(cosmetic)) {
                return false;
            }
        }
        return true;
    }

    private static final String[] COSMETIC = {
            "S03PacketTimeUpdate", "S2BPacketChangeGameState", "S29PacketSoundEffect",
            "S02PacketChat", "S38PacketPlayerListItem", "S3APacketTabComplete",
            "S3BPacketScoreboardObjective", "S3CPacketUpdateScore", "S3DPacketDisplayScoreboard",
            "S3EPacketTeams", "S45PacketTitle", "S47PacketPlayerListHeaderFooter",
            "S2APacketParticles", "S28PacketEffect", "S24PacketBlockAction",
            "S25PacketBlockBreakAnim", "S1CPacketEntityMetadata", "S04PacketEntityEquipment"
    };

    public static String holdKind(Packet<?> packet, boolean send) {
        if (send) {
            return "held-send";
        }
        if (packet instanceof net.minecraft.network.play.server.S08PacketPlayerPosLook
                || packet instanceof net.minecraft.network.play.server.S27PacketExplosion) {
            return "held-self";
        }
        if (packet instanceof net.minecraft.network.play.server.S12PacketEntityVelocity) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null
                    && ((net.minecraft.network.play.server.S12PacketEntityVelocity) packet).getEntityID()
                    == mc.thePlayer.getEntityId()) {
                return "held-self";
            }
        }
        return "held-recv";
    }

    public static java.util.List<Record> records(long millis) {
        long cutoff = System.currentTimeMillis() - millis;
        java.util.List<Record> out = new java.util.ArrayList<Record>();
        synchronized (LOCK) {
            for (Record record : ENTRIES) {
                if (record.at >= cutoff) {
                    out.add(record);
                }
            }
        }
        return out;
    }

    public static String kindOf(Packet<?> packet) {
        if (packet instanceof C02PacketUseEntity) {
            return "attack";
        }
        if (packet instanceof C07PacketPlayerDigging) {
            return "dig";
        }
        if (packet instanceof C08PacketPlayerBlockPlacement) {
            return "place";
        }
        if (packet instanceof C0APacketAnimation) {
            return "swing";
        }
        if (packet instanceof C0BPacketEntityAction) {
            return "action";
        }
        return "packet";
    }

    public static Map<String, Integer> within(long millis) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        long cutoff = System.currentTimeMillis() - millis;
        synchronized (LOCK) {
            for (Record entry : ENTRIES) {
                if (entry.at < cutoff) {
                    continue;
                }
                Integer seen = counts.get(entry.module);
                counts.put(entry.module, seen == null ? entry.count : seen + entry.count);
            }
        }
        return counts;
    }

    public static String summary(long millis, int max) {
        Map<String, Integer> counts = within(millis);
        if (counts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int printed = 0;
        while (printed < max && !counts.isEmpty()) {
            String best = null;
            int bestCount = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > bestCount) {
                    best = entry.getKey();
                    bestCount = entry.getValue();
                }
            }
            if (best == null) {
                break;
            }
            counts.remove(best);
            if (printed > 0) {
                sb.append(' ');
            }
            sb.append(best).append('x').append(bestCount);
            printed++;
        }
        return sb.toString();
    }

    public static String kindsFor(String module, long millis) {
        StringBuilder sb = new StringBuilder();
        long cutoff = System.currentTimeMillis() - millis;
        synchronized (LOCK) {
            Iterator<Record> it = ENTRIES.iterator();
            while (it.hasNext()) {
                Record entry = it.next();
                if (entry.at < cutoff || !module.equals(entry.module)) {
                    continue;
                }
                if (sb.indexOf(entry.kind) < 0) {
                    if (sb.length() > 0) {
                        sb.append('/');
                    }
                    sb.append(entry.kind);
                }
            }
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LOCK) {
            ENTRIES.clear();
        }
    }
}
