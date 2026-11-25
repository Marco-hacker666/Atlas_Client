package atlas.command.commands;

import atlas.Atlas;
import atlas.command.Command;
import atlas.management.PerfCompare;
import atlas.util.ChatUtil;
import atlas.util.render.Effects;
import atlas.util.render.GpuStats;
import atlas.util.render.GpuTimer;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** ".perf": FPS and GPU time of the last few seconds. ".perf compare": fast-blur off against on. */
public class PerfCommand extends Command {
    public PerfCommand() {
        super(new ArrayList<>(Arrays.asList("perf", "fps")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() >= 2 && "compare".equalsIgnoreCase(args.get(1))) {
            if (Minecraft.getMinecraft().theWorld == null) {
                say("&eJoin a world first.");
            } else if (PerfCompare.running()) {
                say("&7A comparison is already running.");
            } else {
                PerfCompare.start();
            }
            return;
        }
        say(String.format("&f%d fps&7  ·  effects %s, %s", Minecraft.getDebugFPS(), Effects.describe(),
                Effects.fastBlur() ? "fast-blur on" : "fast-blur off"));
        if (!GpuTimer.on()) {
            say("&7GPU timing " + GpuTimer.describe() + ".");
            return;
        }
        GpuStats recent = GpuTimer.recent();
        if (recent.frames() == 0) {
            say("&7No GPU timings yet; try again in a few seconds.");
            return;
        }
        say(String.format("&fGPU per frame (%d frames measured): &e%.2f ms&f, blur and glow &e%.2f ms",
                recent.frames(), recent.msPerFrame(GpuStats.FRAME), recent.msPerFrameEndingWith(" blur", " glow")));
        List<String> names = recent.sections();
        StringBuilder parts = new StringBuilder();
        for (int i = 0; i < Math.min(8, names.size()); i++) {
            if (parts.length() > 0) {
                parts.append("&7, ");
            }
            parts.append(String.format("&f%s &e%.2f", names.get(i), recent.msPerFrame(names.get(i))));
        }
        if (parts.length() > 0) {
            say("&7" + parts);
        }
        say("&7.perf compare: fast-blur off against on, about 10 seconds.");
    }

    private static void say(String message) {
        ChatUtil.sendFormatted(Atlas.clientName + message);
    }
}
