package atlas.command.commands;

import atlas.command.Command;
import atlas.sim.SimTargets;
import atlas.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * .targets on|off|stats|reset: measures the forecasts of other players' movement (atlas.sim.SimTargets) against
 * where they really went, next to the straight line the modules use (2026-11-13). Nothing leaves the game.
 */
public class TargetsCommand extends Command {
    public TargetsCommand() {
        super(new ArrayList<String>(Arrays.asList("targets")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        String arg = args.size() > 1 ? args.get(1).toLowerCase() : "stats";
        if (arg.equals("on")) {
            SimTargets.measure(true);
            ChatUtil.sendFormatted("&b[Atlas] &7Measuring the movement forecasts of players within "
                    + (int) SimTargets.RANGE + " blocks. &8.targets stats&7 shows the numbers, &8.targets off&7 stops.&r");
        } else if (arg.equals("off")) {
            SimTargets.measure(false);
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
