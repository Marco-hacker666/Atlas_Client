package atlas.mommy;

import atlas.util.RenderUtil;
import atlas.util.font.FontManager;
import atlas.util.font.impl.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class MommySetupScreen extends GuiScreen {
    private static final int ACCENT = 0x7C9CFF;
    private static final float ROW = 18.0F;
    private static final float BTN = 18.0F;

    private final Supplier<Panic.Plan> plans;
    private final Consumer<Boolean> phoneSetting;
    private final PanicSetup setup = PanicSetup.get();
    private final long openedAt = System.currentTimeMillis();
    private GuiTextField url;

    private GuiTextField command;
    private int commandShell = -1;
    private float scroll;
    private float contentH;
    private String note = "";

    private Boolean picking;
    private volatile List<String[]> programs;
    private final List<Hit> hits = new ArrayList<Hit>();
    private float viewTop;
    private float viewBottom;

    private boolean inView;

    private static final class Hit {
        final float x, y, w, h;
        final Runnable run;
        boolean clipped;

        Hit(float x, float y, float w, float h, Runnable run) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.run = run;
        }
    }

    public MommySetupScreen(Supplier<Panic.Plan> plans, Consumer<Boolean> phoneSetting) {
        this.plans = plans;
        this.phoneSetting = phoneSetting;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        if (this.url == null) {
            this.url = new GuiTextField(0, this.fontRendererObj, 0, 0, 200, 12);
            this.url.setMaxStringLength(400);
            this.url.setEnableBackgroundDrawing(false);
        }
        if (this.command == null) {
            this.command = new GuiTextField(1, this.fontRendererObj, 0, 0, 200, 12);
            this.command.setMaxStringLength(1000);
            this.command.setEnableBackgroundDrawing(false);
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        this.setup.saveQuietly();
    }

    @Override
    public void updateScreen() {
        this.url.updateCursorCounter();
        this.command.updateCursorCounter();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.hits.clear();
        float t = Math.min(1.0F, (System.currentTimeMillis() - this.openedAt) / 200.0F);
        float ease = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
        FontRenderer title = FontManager.productSans20;
        FontRenderer body = FontManager.productSans16;
        boolean ttf = title != null && body != null;
        float lineH = ttf ? (float) body.getHeight() + 3.0F : 11.0F;

        float cardW = Math.min(420.0F, this.width - 24.0F);
        float cardH = Math.min(this.height - 24.0F, 360.0F);
        float cx = (this.width - cardW) / 2.0F;
        float cy = (this.height - cardH) / 2.0F + (1.0F - ease) * 8.0F;
        float pad = 14.0F;
        float inner = cardW - pad * 2.0F;

        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        RenderUtil.drawRect(0, 0, this.width, this.height, alpha(0x05060A, 0.62F * ease));
        RenderUtil.drawRoundedRect(cx, cy, cardW, cardH, 10.0F, alpha(0x11131A, 0.97F * ease));
        RenderUtil.drawRoundedRectOutline(cx, cy, cardW, cardH, 10.0F, 1.0F, alpha(0xFFFFFF, 0.08F), true, true, true, true);
        RenderUtil.drawRoundedRect(cx + pad, cy + 8.0F, 26.0F, 2.0F, 1.0F, alpha(ACCENT, ease));
        text(title, "MOMMY IS HERE!!! setup", cx + pad, cy + pad, 0xFFF5F6FA, ttf);

        float top = cy + pad + (ttf ? (float) title.getHeight() : 10.0F) + 8.0F;
        float bottom = cy + cardH - pad - BTN - 8.0F;
        float y = top - this.scroll;
        float x = cx + pad;

        RenderUtil.scissor(cx, top, cardW, bottom - top);
        this.viewTop = top;
        this.viewBottom = bottom;
        this.inView = true;
        if (this.picking != null) {
            y = this.drawPicker(x, y, inner, lineH, body, ttf, mouseX, mouseY, top, bottom);
        } else {
            y = this.drawMain(x, y, inner, lineH, body, ttf, mouseX, mouseY, top, bottom);
        }
        RenderUtil.releaseScissor();
        this.inView = false;
        this.contentH = y + this.scroll - top;

        float by = cy + cardH - pad - BTN;
        String line = this.note.isEmpty() ? "Keys, action and the rest: the module's settings in the ClickGUI." : this.note;
        text(body, line, x, by + 4.0F, this.note.isEmpty() ? 0xFF8A91A6 : 0xFFFBBF24, ttf);
        this.button(body, ttf, this.picking != null ? "Back" : "Done", cx + cardW - pad - 70.0F, by, 70.0F, true,
                mouseX, mouseY, () -> {
                    if (this.picking != null) {
                        this.picking = null;
                        this.scroll = 0.0F;
                    } else {
                        this.mc.displayGuiScreen(null);
                    }
                });
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
    }

    private float drawMain(float x, float y, float w, float lineH, FontRenderer body, boolean ttf, int mx, int my,
                           float top, float bottom) {
        y = this.heading(body, ttf, "Cover: brought up when it fires", x, y);
        if (this.setup.cover.isEmpty()) {
            text(body, "Nothing yet. Add a page, or pick a window that is open now.", x, y, 0xFF8A91A6, ttf);
            y += lineH;
        }
        for (int i = 0; i < this.setup.cover.size(); i++) {
            final PanicSetup.Cover c = this.setup.cover.get(i);
            String kind = "url".equals(c.type) ? "Page" : "window".equals(c.type) ? "Window" : "Open";
            String label = "window".equals(c.type) ? c.value + (c.label.isEmpty() ? "" : "  ·  " + c.label) : c.value;
            y = this.listRow(body, ttf, kind, label, x, y, w, mx, my, top, bottom, () -> this.setup.cover.remove(c));
        }
        y += 4.0F;
        float fieldW = w - 160.0F;
        RenderUtil.drawRoundedRect(x, y, fieldW, BTN, 5.0F, alpha(0x000000, 0.35F));
        RenderUtil.drawRoundedRectOutline(x, y, fieldW, BTN, 5.0F, 1.0F, alpha(ACCENT, 0.35F), true, true, true, true);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        this.url.width = (int) fieldW - 10;
        this.url.xPosition = (int) x + 5;
        this.url.yPosition = (int) (y + 5.0F);
        if (this.url.getText().isEmpty() && !this.url.isFocused()) {
            text(body, "classroom.google.com", x + 6.0F, y + 4.0F, 0xFF5D6478, ttf);
        }
        this.url.drawTextBox();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        final float fieldY = y;
        this.addHit(new Hit(x, y, fieldW, BTN, () -> this.url.setFocused(true)));
        this.button(body, ttf, "Add page", x + fieldW + 6.0F, y, 70.0F, false, mx, my, this::addUrl);
        this.button(body, ttf, "Pick window", x + fieldW + 80.0F, y, 80.0F, false, mx, my, () -> this.openPicker(true));
        y = fieldY + BTN + 12.0F;

        y = this.heading(body, ttf, "Close these programs", x, y);
        if (this.setup.close.isEmpty()) {
            text(body, "None. Pick from what is open now (Discord, a browser, ...).", x, y, 0xFF8A91A6, ttf);
            y += lineH;
        }
        for (final String n : new ArrayList<String>(this.setup.close)) {
            y = this.listRow(body, ttf, "Close", n, x, y, w, mx, my, top, bottom, () -> this.setup.close.remove(n));
        }
        y += 4.0F;
        this.button(body, ttf, "Pick programs", x, y, 90.0F, false, mx, my,
                () -> this.mc.displayGuiScreen(new ProcessPickerScreen(this)));
        this.button(body, ttf, this.setup.closeForce ? "Force close: on" : "Force close: off", x + 96.0F, y, 110.0F,
                this.setup.closeForce, mx, my, () -> this.setup.closeForce = !this.setup.closeForce);
        y += BTN + 12.0F;

        final int shell = this.plans.get().shell;
        if (shell != this.commandShell) {
            this.commandShell = shell;
            this.command.setText(PanicSetup.firstCommand(shell));
            this.command.setCursorPositionZero();
        }
        y = this.heading(body, ttf, "Custom command (" + PanicCommands.SHELLS[shell] + ", for action Custom)", x, y);
        float cmdW = w - 166.0F;
        RenderUtil.drawRoundedRect(x, y, cmdW, BTN, 5.0F, alpha(0x000000, 0.35F));
        RenderUtil.drawRoundedRectOutline(x, y, cmdW, BTN, 5.0F, 1.0F, alpha(ACCENT, 0.35F), true, true, true, true);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        this.command.width = (int) cmdW - 10;
        this.command.xPosition = (int) x + 5;
        this.command.yPosition = (int) (y + 5.0F);
        if (this.command.getText().isEmpty() && !this.command.isFocused()) {
            text(body, shell == PanicCommands.SHELL_CMD ? "taskkill /im chrome.exe /f"
                    : shell == PanicCommands.SHELL_POWERSHELL ? "Stop-Process -Name chrome" : "pkill -x Discord",
                    x + 6.0F, y + 4.0F, 0xFF5D6478, ttf);
        }
        this.command.drawTextBox();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        this.addHit(new Hit(x, y, cmdW, BTN, () -> this.command.setFocused(true)));
        this.button(body, ttf, "Save", x + cmdW + 6.0F, y, 50.0F, true, mx, my, this::saveCommand);
        this.button(body, ttf, "Open file", x + cmdW + 62.0F, y, 104.0F, false, mx, my, () -> {
            java.io.File f = PanicHooks.editCustom(shell);
            this.commandShell = -1;
            this.note = "Opened " + f.getName() + ": several lines go there.";
        });
        y += BTN + 3.0F;
        int more = PanicSetup.commandLines(shell) - 1;
        text(body, more > 0 ? "The file has " + more + " more line(s) after this one; they run too."
                : "One line here; for several lines use Open file.", x, y, 0xFF8A91A6, ttf);
        y += lineH + 10.0F;

        y = this.heading(body, ttf, "Phone remote (same Wi-Fi)", x, y);
        text(body, PanicRemote.running() ? "On. Pair once, then keep the page on the phone's home screen."
                : "Off. Pairing (or the phone-remote setting) turns it on.", x, y, 0xFF8A91A6, ttf);
        y += lineH + 2.0F;
        this.button(body, ttf, "Pair phone", x, y, 80.0F, false, mx, my, this::pair);
        this.button(body, ttf, "New link", x + 86.0F, y, 70.0F, false, mx, my, () -> {
            this.setup.newRemoteKey();
            this.note = "New link made: pair the phone again.";
        });
        y += BTN + 12.0F;

        y = this.heading(body, ttf, "Try it", x, y);
        text(body, "Runs it with no sleep, shut down, lock, leaving or quitting; back after 3 seconds.", x, y,
                0xFF8A91A6, ttf);
        y += lineH + 2.0F;
        this.button(body, ttf, "Test now", x, y, 80.0F, true, mx, my, this::test);
        y += BTN + 6.0F;
        return y;
    }

    private float drawPicker(float x, float y, float w, float lineH, FontRenderer body, boolean ttf, int mx, int my,
                             float top, float bottom) {
        y = this.heading(body, ttf, this.picking ? "Pick a window to bring up" : "Pick a program to close", x, y);
        List<String[]> list = this.programs;
        if (list == null) {
            text(body, "Looking at what is open…", x, y, 0xFF8A91A6, ttf);
            return y + lineH;
        }
        if (list.isEmpty()) {
            text(body, "Nothing found.", x, y, 0xFF8A91A6, ttf);
            return y + lineH;
        }
        for (final String[] p : list) {
            boolean over = mx >= x && mx <= x + w && my >= y && my <= y + ROW && my >= top && my <= bottom;
            RenderUtil.drawRoundedRect(x, y, w, ROW - 2.0F, 5.0F, alpha(0xFFFFFF, over ? 0.09F : 0.04F));
            text(body, clip(body, p[0], 150.0F, ttf), x + 6.0F, y + 4.0F, 0xFFE6E8F0, ttf);
            if (!p[1].isEmpty()) {
                text(body, clip(body, p[1], w - 170.0F, ttf), x + 160.0F, y + 4.0F, 0xFF8A91A6, ttf);
            }
            this.addHit(new Hit(x, y, w, ROW - 2.0F, () -> {
                if (Boolean.TRUE.equals(this.picking)) {
                    this.setup.cover.add(new PanicSetup.Cover("window", p[0], p[1]));
                    this.note = "Added " + p[0] + " to the cover.";
                } else if (!this.setup.close.contains(p[0])) {
                    this.setup.close.add(p[0]);
                    this.note = p[0] + " will be closed.";
                }
                this.setup.saveQuietly();
                this.picking = null;
                this.scroll = 0.0F;
            }));
            y += ROW;
        }
        return y;
    }

    private void addHit(Hit h) {
        h.clipped = this.inView;
        this.hits.add(h);
    }

    private float heading(FontRenderer body, boolean ttf, String s, float x, float y) {
        text(body, s.toUpperCase(), x, y, 0xFF9AA3BA, ttf);
        return y + (ttf ? (float) body.getHeight() : 9.0F) + 6.0F;
    }

    private float listRow(FontRenderer body, boolean ttf, String kind, String label, float x, float y, float w, int mx,
                          int my, float top, float bottom, Runnable remove) {
        RenderUtil.drawRoundedRect(x, y, w, ROW - 2.0F, 5.0F, alpha(0xFFFFFF, 0.04F));
        RenderUtil.drawRoundedRect(x + 4.0F, y + 3.0F, 46.0F, ROW - 8.0F, 4.0F, alpha(ACCENT, 0.22F));
        centeredIn(body, kind, x + 4.0F, y + 3.0F, 46.0F, ROW - 8.0F, 0xFFDDE3FF, ttf);
        text(body, clip(body, label, w - 80.0F, ttf), x + 56.0F, y + 4.0F, 0xFFE6E8F0, ttf);
        boolean overX = mx >= x + w - 20.0F && mx <= x + w && my >= y && my <= y + ROW && my >= top && my <= bottom;
        text(body, "×", x + w - 13.0F, y + 3.0F, overX ? 0xFFF87171 : 0xFF8A91A6, ttf);
        this.addHit(new Hit(x + w - 20.0F, y, 20.0F, ROW - 2.0F, () -> {
            remove.run();
            this.setup.saveQuietly();
        }));
        return y + ROW;
    }

    private void button(FontRenderer body, boolean ttf, String label, float x, float y, float w, boolean primary,
                        int mx, int my, Runnable run) {
        boolean over = mx >= x && mx <= x + w && my >= y && my <= y + BTN;
        RenderUtil.drawRoundedRect(x, y, w, BTN, 5.0F, primary ? alpha(ACCENT, over ? 1.0F : 0.85F)
                : alpha(0xFFFFFF, over ? 0.13F : 0.07F));
        centeredIn(body, label, x, y, w, BTN, 0xFFFFFFFF, ttf);
        this.addHit(new Hit(x, y, w, BTN, run));
    }

    private void saveCommand() {
        try {
            PanicSetup.setFirstCommand(this.commandShell, this.command.getText());
            this.command.setFocused(false);
            this.note = this.command.getText().trim().isEmpty() ? "Command removed."
                    : "Saved. Set action to Custom to use it.";
        } catch (Exception e) {
            this.note = "Could not save: " + e.getMessage();
        }
    }

    private void addUrl() {
        String u = PanicSetup.normalizeUrl(this.url.getText());
        if (!PanicSetup.validUrl(u)) {
            this.note = "That is not a web address.";
            return;
        }
        this.setup.cover.add(new PanicSetup.Cover("url", u, ""));
        this.setup.saveQuietly();
        this.url.setText("");
        this.note = "Added.";
    }

    private void openPicker(boolean cover) {
        this.picking = cover;
        this.programs = null;
        this.scroll = 0.0F;
        Thread t = new Thread(() -> {
            List<String[]> all = PanicPlatform.openPrograms();
            List<String[]> out = new ArrayList<String[]>();
            List<String> seen = new ArrayList<String>();
            for (String[] p : all) {
                String key = cover ? p[0] + "\n" + p[1] : p[0].toLowerCase();
                if (!seen.contains(key)) {
                    seen.add(key);
                    out.add(p);
                }
            }
            this.programs = out;
        }, "Atlas mommy programs");
        t.setDaemon(true);
        t.start();
    }

    private void pair() {
        this.phoneSetting.accept(true);
        if (!PanicRemote.start(this.plans)) {
            this.note = "Could not open port " + this.setup.remotePort + ".";
            return;
        }
        PanicPlatform.openTarget(PanicRemote.pairUrl());
        this.note = "The pairing page opened in your browser.";
    }

    private void test() {
        Panic.Plan p = this.plans.get();
        if (p.action != Panic.ACTION_BLACK && p.action != Panic.ACTION_CUSTOM) {
            p.action = Panic.ACTION_NOTHING;
        }
        if (p.action == Panic.ACTION_CUSTOM) {
            p.action = Panic.ACTION_NOTHING;
        }
        p.leaveServer = false;
        p.quitGame = false;
        p.closeApps = false;
        this.mc.displayGuiScreen(null);
        Panic.fire(p);
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(3000L);
            } catch (InterruptedException ignored) {
                return;
            }
            Panic.restoreLater();
        }, "Atlas mommy test");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 0) {
            return;
        }
        this.url.setFocused(false);
        this.command.setFocused(false);
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit h = this.hits.get(i);
            if (h.clipped && (mouseY < this.viewTop || mouseY > this.viewBottom)) {
                continue;
            }
            if (mouseX >= h.x && mouseX <= h.x + h.w && mouseY >= h.y && mouseY <= h.y + h.h) {
                h.run.run();
                return;
            }
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0) {
            float max = Math.max(0.0F, this.contentH - (Math.min(this.height - 24.0F, 360.0F) - 80.0F));
            this.scroll = Math.max(0.0F, Math.min(max, this.scroll - Math.signum(d) * 24.0F));
        }
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            if (this.picking != null) {
                this.picking = null;
            } else {
                this.mc.displayGuiScreen(null);
            }
            return;
        }
        if (this.url.isFocused()) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                this.addUrl();
                return;
            }
            this.url.textboxKeyTyped(typed, key);
        } else if (this.command.isFocused()) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                this.saveCommand();
                return;
            }
            this.command.textboxKeyTyped(typed, key);
        }
    }

    private static double width(FontRenderer font, String s, boolean ttf) {
        return ttf ? font.getStringWidth(s) : net.minecraft.client.Minecraft.getMinecraft().fontRendererObj.getStringWidth(s);
    }

    private static String clip(FontRenderer font, String s, float max, boolean ttf) {
        if (width(font, s, ttf) <= max) {
            return s;
        }
        String t = s;
        while (t.length() > 1 && width(font, t + "…", ttf) > max) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "…";
    }

    private static void text(FontRenderer font, String s, float x, float y, int colour, boolean ttf) {
        if (ttf) {
            font.drawString(s, x, y, colour, false);
        } else {
            net.minecraft.client.Minecraft.getMinecraft().fontRendererObj.drawString(s, (int) x, (int) y, colour);
        }
    }

    private static void centeredIn(FontRenderer font, String s, float x, float y, float w, float h, int colour,
                                   boolean ttf) {
        double tw = width(font, s, ttf);
        double th = ttf ? font.getHeight() : 8.0;
        text(font, s, (float) (x + (w - tw) / 2.0), (float) (y + (h - th) / 2.0 + (ttf ? 1.0 : 0.0)), colour, ttf);
    }

    private static int alpha(int rgb, float a) {
        return (Math.max(8, Math.min(255, Math.round(a * 255.0F))) << 24) | (rgb & 0xFFFFFF);
    }
}
