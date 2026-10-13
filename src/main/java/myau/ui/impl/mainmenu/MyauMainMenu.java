package myau.ui.impl.mainmenu;

import myau.mixin.IAccessorGuiScreen;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.gui.GuiYesNoCallback;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.realms.RealmsBridge;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.demo.DemoWorldServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The start screen (rewritten 2026-10-12, owner: "整個大改"; the six old
 * backgrounds, their picker and the glass card are gone), from the preview
 * the owner approved (design/menu-preview.html): a sunrise-graded panorama
 * (MenuBackground), a large condensed MYAU / ATLAS on the left with the
 * menu under it; a hovered entry slides right behind an orange bar.
 *
 * 2026-10-13 (owner, from player feedback): the AURORA look and the pill
 * that switched to it are gone, and so is the "SIGNED IN AS / LAST SERVER"
 * block at the bottom right ("looks AI-made"). The menu now fades in only
 * once its text is rasterised (TextImage works off the render thread),
 * so opening it no longer freezes a frame and skips the fade.
 *
 * Every text is a TextImage (crisp at any size). Clicks are matched to the
 * boxes drawn in the frame the player saw.
 */
public class MyauMainMenu extends GuiScreen implements GuiYesNoCallback {
    /* Outfit and Bebas Neue (SIL OFL 1.1, licences in assets/myau/font/licenses). */
    private static final String REGULAR = "outfit-regular.ttf";
    private static final String MEDIUM = "outfit-medium.ttf";
    private static final String BOLD = "bebas-neue.ttf";
    private static final int ORANGE = 0xFFFFB46B;

    private static final int SINGLEPLAYER = 1;
    private static final int MULTIPLAYER = 2;
    private static final int OPTIONS = 0;
    private static final int QUIT = 4;
    private static final int MODS = 6;
    private static final int DEMO = 11;
    private static final int ALTS = 14;
    private static final int SETUP = 16;

    private static final class Hit {
        final int action;
        final float x1, y1, x2, y2;

        Hit(int action, float x1, float y1, float x2, float y2) {
            this.action = action;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Float> anims = new HashMap<>();
    private long openedAt;
    /* The text is ready and the fade-in has started. */
    private boolean shown;
    private long lastFrame;
    private float dt = 0.016F;
    private float fade;
    private boolean realmsInit;
    private GuiScreen realmsNotice;

    public MyauMainMenu() {
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
    }

    @Override
    public void initGui() {
        myau.setup.SetupHook.onMainMenu(this);
        this.openedAt = System.currentTimeMillis();
        this.shown = false;
        this.lastFrame = 0L;
        this.mc.setConnectedToRealms(false);
        if (Minecraft.getMinecraft().gameSettings.getOptionOrdinalValue(GameSettings.Options.REALMS_NOTIFICATIONS)
                && !this.realmsInit) {
            this.realmsNotice = new RealmsBridge().getNotificationScreen(this);
            this.realmsInit = true;
        }
        if (this.realmsShown()) {
            this.realmsNotice.setGuiSize(this.width, this.height);
            this.realmsNotice.initGui();
        }
    }

    private boolean realmsShown() {
        return Minecraft.getMinecraft().gameSettings.getOptionOrdinalValue(GameSettings.Options.REALMS_NOTIFICATIONS)
                && this.realmsNotice != null;
    }

    @Override
    public void updateScreen() {
        if (this.realmsShown()) {
            this.realmsNotice.updateScreen();
        }
    }

    // ---- helpers -------------------------------------------------------

    private float ease(String key, float target, float speed) {
        Float now = this.anims.get(key);
        float v = now == null ? target : now;
        v += (target - v) * Math.min(1.0F, this.dt * speed);
        this.anims.put(key, v);
        return v;
    }

    /** {@code color} with its alpha times {@code k} and the opening fade. */
    private int alpha(int color, float k) {
        int a = Math.round((color >>> 24) * Math.max(0.0F, Math.min(1.0F, k)) * this.fade);
        return a << 24 | color & 0xFFFFFF;
    }

    private static boolean over(float mx, float my, float x1, float y1, float x2, float y2) {
        return mx >= x1 && mx <= x2 && my >= y1 && my <= y2;
    }

    private static int mix(int from, int to, float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        int a1 = from >>> 24, r1 = from >> 16 & 255, g1 = from >> 8 & 255, b1 = from & 255;
        int a2 = to >>> 24, r2 = to >> 16 & 255, g2 = to >> 8 & 255, b2 = to & 255;
        return Math.round(a1 + (a2 - a1) * t) << 24 | Math.round(r1 + (r2 - r1) * t) << 16
                | Math.round(g1 + (g2 - g1) * t) << 8 | Math.round(b1 + (b2 - b1) * t);
    }

    private static String version() {
        String v = myau.Myau.version == null ? "2.0" : myau.Myau.version;
        return "V" + v.toUpperCase();
    }

    // ---- drawing -------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        long now = System.nanoTime();
        this.dt = this.lastFrame == 0L ? 0.016F : Math.min(0.05F, (now - this.lastFrame) / 1.0E9F);
        this.lastFrame = now;
        long millis = System.currentTimeMillis();
        this.hits.clear();
        MenuBackground.draw(this.width, this.height, mouseX, mouseY);
        if (!this.shown) {
            /* Wait (black) for the fonts, then lay the menu out unseen once so
               every string goes to the worker, and fade in when they are all
               back; never longer than 1.5 s. */
            long waited = millis - this.openedAt;
            if (TextImage.ready() || waited > 1500L) {
                this.fade = 0.0F;
                this.drawCinematic(mouseX, mouseY);
                if (!TextImage.busy() || waited > 1500L) {
                    this.shown = true;
                    this.openedAt = millis;
                }
            }
            this.hits.clear();
            MenuBackground.rect(0.0F, 0.0F, (float) this.width, (float) this.height, 0xFF000000);
            return;
        }
        float t = Math.min(1.0F, (millis - this.openedAt) / 700.0F);
        this.fade = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
        this.drawCinematic(mouseX, mouseY);
        /* Black lifting off as the screen opens. */
        if (this.fade < 1.0F) {
            MenuBackground.rect(0.0F, 0.0F, (float) this.width, (float) this.height,
                    Math.round(0xFF * (1.0F - this.fade)) << 24);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (this.realmsShown()) {
            this.realmsNotice.drawScreen(mouseX, mouseY, partialTicks);
        }
    }

    private void drawCinematic(float mx, float my) {
        float title = Math.max(40.0F, Math.min(110.0F, this.height * 0.17F));
        float slide = (1.0F - this.fade) * -14.0F;
        float blockH = 15.0F + title * 0.86F + title * 0.72F + 26.0F + 4 * 25.0F + 20.0F;
        float y = Math.max(this.height * 0.1F, (this.height - blockH) / 2.0F);
        float x = this.width * 0.06F + slide;

        TextImage.draw("MINECRAFT 1.8.9  ·  " + version(), MEDIUM, 7.5F, 0.45F, x, y, alpha(ORANGE, 1.0F), 0, 1.0F);
        y += 15.0F;
        TextImage.draw("MYAU", BOLD, title, 0.02F, x - 2.0F, y, alpha(0xFFFFFFFF, 1.0F), 0, 1.0F);
        y += title * 0.86F;
        TextImage.draw("ATLAS", BOLD, title, 0.02F, x - 2.0F, y, alpha(ORANGE, 1.0F), 0, 1.0F);
        y += title * 0.72F + 26.0F;

        boolean demo = this.mc.isDemo();
        String[] names = demo ? new String[]{"PLAY DEMO"} : new String[]{"PLAY", "MULTIPLAYER", "ALT MANAGER", "MODS"};
        int[] actions = demo ? new int[]{DEMO} : new int[]{SINGLEPLAYER, MULTIPLAYER, ALTS, MODS};
        float size = 12.0F;
        /* 2026-10-13 (owner: PLAY's bar never drew back): one bar only. It
           marks the entry under the pointer, and PLAY when the pointer is on
           none, so it slides from entry to entry and PLAY's shrinks away. */
        int pointed = -1;
        for (int i = 0; i < names.length; i++) {
            float w = TextImage.width(names[i], MEDIUM, size, 0.12F, 1.0F);
            float iy = y + i * 25.0F;
            if (over(mx, my, x - 4, iy - 5, x + 40 + w, iy + size + 5)) {
                pointed = i;
            }
        }
        int marked = pointed < 0 ? 0 : pointed;
        for (int i = 0; i < names.length; i++) {
            float w = TextImage.width(names[i], MEDIUM, size, 0.12F, 1.0F);
            float lit = ease("c:" + names[i], i == marked ? 1.0F : 0.0F, 12.0F);
            float bar = lit;
            if (bar > 0.01F) {
                float by = y + size * 0.36F;
                MenuBackground.rect(x, by - 1.5F, x + 14.0F * bar, by + 2.5F, alpha(0x30FF9A3C, bar));
                MenuBackground.rect(x, by, x + 14.0F * bar, by + 1.0F, alpha(ORANGE, bar));
            }
            float tx = x + 21.0F * bar + lit * 6.0F;
            int colour = mix(0xB8FFFFFF, 0xFFFFFFFF, lit);
            TextImage.draw(names[i], MEDIUM, size, 0.12F, tx, y, alpha(colour, 1.0F), 0, 1.0F);
            this.hits.add(new Hit(actions[i], x - 4, y - 5, x + 40 + w, y + size + 5));
            y += 25.0F;
        }
        y += 8.0F;
        String[] small = {"SETTINGS", "SETUP", "QUIT"};
        int[] smallActions = {OPTIONS, SETUP, QUIT};
        float sx = x;
        for (int i = 0; i < small.length; i++) {
            float w = TextImage.width(small[i], REGULAR, 8.0F, 0.2F, 1.0F);
            boolean hovered = over(mx, my, sx - 3, y - 4, sx + w + 3, y + 12);
            float lit = ease("c:" + small[i], hovered ? 1.0F : 0.0F, 12.0F);
            TextImage.draw(small[i], REGULAR, 8.0F, 0.2F, sx, y, alpha(mix(0xA0FFFFFF, 0xFFFFFFFF, lit), 1.0F), 0, 1.0F);
            this.hits.add(new Hit(smallActions[i], sx - 3, y - 4, sx + w + 3, y + 12));
            sx += w + 18.0F;
        }
    }

    // ---- input ---------------------------------------------------------

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton == 0) {
            for (int i = this.hits.size() - 1; i >= 0; i--) {
                Hit hit = this.hits.get(i);
                if (over(mouseX, mouseY, hit.x1, hit.y1, hit.x2, hit.y2)) {
                    this.mc.getSoundHandler().playSound(
                            PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
                    this.perform(hit.action);
                    return;
                }
            }
        }
        if (this.realmsShown()) {
            ((IAccessorGuiScreen) this.realmsNotice).callMouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    private void perform(int action) {
        switch (action) {
            case SINGLEPLAYER:
                this.mc.displayGuiScreen(new GuiSelectWorld(this));
                break;
            case MULTIPLAYER:
                this.mc.displayGuiScreen(new GuiMultiplayer(this));
                break;
            case OPTIONS:
                this.mc.displayGuiScreen(new GuiOptions(this, this.mc.gameSettings));
                break;
            case QUIT:
                this.mc.shutdown();
                break;
            case MODS:
                this.mc.displayGuiScreen(new net.minecraftforge.fml.client.GuiModList(this));
                break;
            case ALTS:
                this.mc.displayGuiScreen(new me.ksyz.accountmanager.gui.GuiAccountManager(this));
                break;
            case SETUP:
                myau.setup.SetupHook.open();
                break;
            case DEMO:
                this.mc.launchIntegratedServer("Demo_World", "Demo_World", DemoWorldServer.demoWorldSettings);
                break;
            default:
                break;
        }
    }

    @Override
    public void confirmClicked(boolean result, int id) {
        this.mc.displayGuiScreen(this);
    }

    @Override
    public void onGuiClosed() {
        if (this.realmsNotice != null) {
            this.realmsNotice.onGuiClosed();
        }
    }
}
