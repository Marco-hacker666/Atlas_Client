package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.management.UsageReporter;
import atlas.module.Module;
import atlas.nickresolver.IdentityLabels;
import atlas.nickresolver.NickSettings;
import atlas.nickresolver.NicknameResolver;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

import java.io.File;

public class Denick extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty nametag = new BooleanProperty("nametag", true);
    public final BooleanProperty chat = new BooleanProperty("chat", true);
    public final BooleanProperty probable = new BooleanProperty("probable", true);
    public final BooleanProperty unresolved = new BooleanProperty("unresolved", true);
    public final BooleanProperty skinOwner = new BooleanProperty("skin-owner", false);
    public final ModeProperty vanillaTag = new ModeProperty("vanilla-tag", 0, new String[]{"ABOVE", "INLINE"});
    public final BooleanProperty share = new BooleanProperty("share", true);

    public final BooleanProperty log = new BooleanProperty("log", true);

    public final BooleanProperty showReal = new BooleanProperty("show-real", false);

    private final boolean[] seen = new boolean[9];
    private boolean seenValid;

    public Denick() {
        super("Denick", true, true);
    }

    public static void install() {
        try {
            NicknameResolver.installHosted(new File(mc.mcDataDir, "config/Atlas/denick"), Denick::atlasDrawsTag, ".denick");
        } catch (Throwable t) {
            System.err.println("[Atlas] Denick could not start: " + t);
        }
    }

    private static boolean atlasDrawsTag(EntityPlayer player) {
        if (Atlas.moduleManager == null) {
            return false;
        }
        NameTags nameTags = (NameTags) Atlas.moduleManager.modules.get(NameTags.class);
        if (nameTags != null && nameTags.isEnabled() && nameTags.shouldRenderTags(player)) {
            return true;
        }
        ESP esp = (ESP) Atlas.moduleManager.modules.get(ESP.class);
        return esp != null && esp.isEnabled() && !esp.isOutlineEnabled();
    }

    public static String tag(EntityLivingBase entity) {
        NicknameResolver core = NicknameResolver.get();
        if (core == null || !(entity instanceof EntityPlayer) || entity == mc.thePlayer) {
            return null;
        }
        NickSettings settings = core.settings();
        if (!settings.enabled || !settings.showNametag) {
            return null;
        }
        return IdentityLabels.nametag(core.resolver().forEntity(entity), settings);
    }

    public static void observe(net.minecraft.network.Packet<?> packet) {
        NicknameResolver core = NicknameResolver.get();
        if (core == null || Atlas.moduleManager == null) {
            return;
        }
        Module module = Atlas.moduleManager.modules.get(Denick.class);
        if (module != null && module.isEnabled()) {
            core.onPacket(packet);
        }
    }

    @EventTarget(whenDisabled = true)
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.POST) {
            return;
        }
        NicknameResolver core = NicknameResolver.get();
        if (core == null) {
            return;
        }
        NickSettings s = core.settings();
        boolean[] now = {s.enabled, s.showNametag, s.showChat, s.showProbable, s.showUnresolved, s.showSkinOwner,
                s.sessionLog, s.showReal, s.nametagMode == NickSettings.NametagMode.INLINE};
        boolean[] ours = {this.isEnabled(), this.nametag.getValue(), this.chat.getValue(), this.probable.getValue(),
                this.unresolved.getValue(), this.skinOwner.getValue(), this.log.getValue(), this.showReal.getValue(),
                this.vanillaTag.getValue() == 1};
        String[] keys = {"enabled", "nametag", "chat", "probable", "unresolved", "skinowner", "log", "real", null};
        for (int i = 0; i < now.length; i++) {
            if (this.seenValid && now[i] != this.seen[i]) {
                this.adopt(i, now[i]);
                this.seen[i] = now[i];
            } else if (ours[i] != now[i]) {
                if (keys[i] != null) {
                    s.setToggle(keys[i], ours[i]);
                } else {
                    s.setNametagMode(ours[i] ? NickSettings.NametagMode.INLINE : NickSettings.NametagMode.ABOVE);
                }
                this.seen[i] = ours[i];
            } else {
                this.seen[i] = now[i];
            }
        }
        this.seenValid = true;

        boolean cloud = this.isEnabled() && this.share.getValue() && Boolean.TRUE.equals(UsageReporter.consent());
        if (s.cloudEnabled != cloud) {
            core.setCloudConsent(cloud);
        }
    }

    private void adopt(int i, boolean value) {
        switch (i) {
            case 0:
                if (this.isEnabled() != value) {
                    this.setEnabled(value);
                }
                break;
            case 1:
                this.nametag.setValue(value);
                break;
            case 2:
                this.chat.setValue(value);
                break;
            case 3:
                this.probable.setValue(value);
                break;
            case 4:
                this.unresolved.setValue(value);
                break;
            case 5:
                this.skinOwner.setValue(value);
                break;
            case 6:
                this.log.setValue(value);
                break;
            case 7:
                this.showReal.setValue(value);
                break;
            default:
                this.vanillaTag.setValue(value ? 1 : 0);
                break;
        }
    }
}
