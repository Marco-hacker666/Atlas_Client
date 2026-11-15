package atlas.command.commands;

import atlas.command.Command;
import atlas.sim.SimTargets;
import atlas.util.ChatUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * .targets on|off|stats|reset: measures the forecasts of other players' movement (atlas.sim.SimTargets) against
 * where they really went, next to the straight line the modules use (2026-11-13). Nothing leaves the game.
 *
 * 2026-11-15 (owner: "make it on by default for me, I forget"): on and off are kept in config/Atlas/targets.txt, and
 * {@link #restore} puts the measuring back on at start when it was on. Off for anyone who never turned it on.
 */
public class TargetsCommand extends Command {
    private static final File SAVED = new File("./config/Atlas/targets.txt");

    /** At start: measuring on again if .targets on was the last word. */
    public static void restore() {
        try {
            if (SAVED.isFile() && "on".equals(new String(Files.readAllBytes(SAVED.toPath()),
                    StandardCharsets.UTF_8).trim())) {
                SimTargets.measure(true);
            }
        } catch (Exception ignored) {
        }
    }

    private static void save(boolean on) {
        try {
            File dir = SAVED.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            Files.write(SAVED.toPath(), (on ? "on" : "off").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
    public TargetsCommand() {
        super(new ArrayList<String>(Arrays.asList("targets")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        String arg = args.size() > 1 ? args.get(1).toLowerCase() : "stats";
        if (arg.equals("on")) {
            SimTargets.measure(true);
            save(true);
            ChatUtil.sendFormatted("&b[Atlas] &7Measuring the movement forecasts of players within "
                    + (int) SimTargets.RANGE + " blocks, from now on every time the game starts. &8.targets stats&7 "
                    + "shows the numbers, &8.targets off&7 stops.&r");
        } else if (arg.equals("off")) {
            SimTargets.measure(false);
            save(false);
            ChatUtil.sendFormatted("&b[Atlas] &7Measuring is off. " + SimTargets.summary() + "&r");
        } else if (arg.equals("reset")) {
            SimTargets.resetScore();
            ChatUtil.sendFormatted("&b[Atlas] &7The forecast numbers start again.&r");
        } else {
            ChatUtil.sendFormatted("&b[Atlas] &7Forecast error in blocks, simulation vs straight line ("
                    + (SimTargets.measuring() ? "&ameasuring&7, following " + SimTargets.followed() : "&coff&7")
                    + "): " + SimTargets.summary() + "&r");
        }
    }
}
