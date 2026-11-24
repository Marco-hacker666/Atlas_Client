package atlas.command.commands;

import com.google.common.collect.Iterables;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import atlas.Atlas;
import atlas.command.Command;
import atlas.enums.ChatColors;
import atlas.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class DenickCommand extends Command {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final List<String> RESOLVER = Arrays.asList(
            "list", "who", "lookup", "guess", "toggle", "set", "mode", "cloud", "channels", "status", "clear", "save", "help");

    public DenickCommand() {
        super(new ArrayList<>(Collections.singletonList("denick")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() < 2 || RESOLVER.contains(args.get(1).toLowerCase(Locale.ROOT))) {
            if (resolver(args.subList(1, args.size()).toArray(new String[0]))) {
                return;
            }
        }
        if (args.size() < 2) {
            ChatUtil.sendFormatted(String.format("%sUsage: .%s <&oname&r>&r", Atlas.clientName, args.get(0).toLowerCase(Locale.ROOT)));
        } else {
            NetworkPlayerInfo playerInfo = mc.getNetHandler().getPlayerInfo(ChatColors.formatColor(args.get(1)));
            if (playerInfo != null) {
                GameProfile gameProfile = playerInfo.getGameProfile();
                Property property = Iterables.getFirst(gameProfile.getProperties().get("textures"), null);
                if (property != null) {
                    String code = new String(Base64.getDecoder().decode(property.getValue().getBytes(StandardCharsets.UTF_8)));
                    String name = code.contains("profileName\" : \"") ? code.split("profileName\" : \"")[1].split("\"")[0] : "?";
                    String uuid = code.contains("profileId\" : \"") ? code.split("profileId\" : \"")[1].split("\"")[0] : "?";
                    ChatUtil.sendRaw(
                            String.format(
                                    ChatColors.formatColor("%s%s&r -> %s (&o%s&r)&r"),
                                    ChatColors.formatColor(Atlas.clientName),
                                    gameProfile.getName().replace("§", "&"),
                                    name,
                                    uuid
                            )
                    );
                    if (!uuid.isEmpty() && !uuid.equals("?")) {
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(uuid), null);
                    }
                } else {
                    ChatUtil.sendRaw(
                            String.format(
                                    ChatColors.formatColor("%sNo textures for entity with name &o%s&r"),
                                    ChatColors.formatColor(Atlas.clientName),
                                    args.get(1)
                            )
                    );
                }
            } else {
                ChatUtil.sendRaw(
                        String.format(
                                ChatColors.formatColor("%sNo entity with name &o%s&r"),
                                ChatColors.formatColor(Atlas.clientName),
                                args.get(1)
                        )
                );
            }
            resolver(new String[]{"who", args.get(1)});
        }
    }

    private static boolean resolver(String[] args) {
        atlas.nickresolver.NicknameResolver core = atlas.nickresolver.NicknameResolver.get();
        if (core == null || core.command() == null || mc.thePlayer == null) {
            return false;
        }
        try {
            core.command().processCommand(mc.thePlayer, args);
        } catch (Exception e) {
            ChatUtil.sendRaw(ChatColors.formatColor(Atlas.clientName) + e.getMessage());
        }
        return true;
    }
}
