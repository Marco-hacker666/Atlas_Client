package atlas.ui;

import atlas.management.UsageReporter;
import atlas.util.RenderUtil;
import atlas.util.font.FontManager;
import atlas.util.font.impl.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;

public final class ConsentScreen extends GuiScreen {
    private static final int ACCENT = 0x7C9CFF;
    private static final String TITLE = "Share usage with the Atlas developer?";
    private static final String[] INTRO = {
            "Atlas can tell its developer who is using it, so problems can be found and fixed. Nothing is sent "
                    + "unless you agree."};
    private static final String[] SENT = {
            "Your Minecraft name and UUID (checked with Mojang; your login never leaves you and Mojang)",
            "The server you play on",
            "Your IP address and rough location (city, country): seen by the Atlas owner only",
            "Atlas version, FPS, ping and the modules you have on",
            "Your system: OS, Java, memory, graphics card, screen",
            "Your mod list (names, versions) and resource packs",
            "Anticheat alerts, Atlas debug logs and crash reports from your game",
            "Bug reports you send will show they came from you",
            "Denick: other players you see (shown name, UUID, skin) for the shared nickname list"};
    private static final String[] OUTRO = {
            "Only the Atlas owner and admins can see it (the IP and location: the owner only); the public Atlas site "
                    + "shows only a name you pick. Deleted "
                    + "after 30 days. Change it any time in Client Settings > Privacy."};
    private static final String NAME_TITLE = "Pick your public name";
    private static final String[] NAME_TEXT = {
            "This is the only thing other players see on the Atlas site. Your Minecraft name stays hidden.",
            "2-20 letters, digits, spaces, _ - or ."};
    private static final float BUTTON_H = 20.0F;
    private static final float FIELD_H = 20.0F;

    private final Runnable after;
    private long openedAt = System.currentTimeMillis();
    private int step;
    private GuiTextField name;
    private String problem = "";
    private float cardX;
    private float cardY;
    private float cardW;
    private float cardH;
    private float yesX;
    private float noX;
    private float buttonY;
    private float buttonW;
    private float fieldY;

    public ConsentScreen(Runnable after) {
        this.after = after;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        this.field(100);
    }

    private void field(int width) {
        if (this.name != null && this.name.getWidth() == width) {
            return;
        }
        String keep = this.name == null ? UsageReporter.alias() : this.name.getText();
        boolean focused = this.name == null ? this.step == 1 : this.name.isFocused();
        this.name = new GuiTextField(0, this.fontRendererObj, 0, 0, width, (int) FIELD_H);
        this.name.setMaxStringLength(20);
        this.name.setEnableBackgroundDrawing(false);
        this.name.setText(keep);
        this.name.setFocused(focused);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {
        if (this.name != null) {
            this.name.updateCursorCounter();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        float t = Math.min(1.0F, (System.currentTimeMillis() - this.openedAt) / 220.0F);
        float ease = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
        FontRenderer title = FontManager.productSans20;
        FontRenderer body = FontManager.productSans16;
        boolean ttf = title != null && body != null;

        this.cardW = Math.min(310.0F, this.width - 24.0F);
        float pad = 16.0F;
        float textW = this.cardW - pad * 2.0F;
        float lineH = ttf ? (float) body.getHeight() + 3.0F : 11.0F;
        float titleH = ttf ? (float) title.getHeight() + 4.0F : 12.0F;
        List<String> intro = wrap(this.step == 0 ? INTRO : NAME_TEXT, textW, body, ttf);
        List<String> outro = this.step == 0 ? wrap(OUTRO, textW, body, ttf) : new ArrayList<String>();
        List<List<String>> sent = new ArrayList<List<String>>();
        int sentLines = 0;
        if (this.step == 0) {
            for (String s : SENT) {
                List<String> lines = wrap(new String[]{s}, textW - 10.0F, body, ttf);
                sent.add(lines);
                sentLines += lines.size();
            }
        }
        float middle = this.step == 0
                ? 8.0F + sentLines * lineH + 8.0F + outro.size() * lineH
                : 10.0F + FIELD_H + 4.0F + lineH;
        this.cardH = pad + titleH + 6.0F + intro.size() * lineH + middle + 14.0F + BUTTON_H + pad;
        this.cardX = (this.width - this.cardW) / 2.0F;
        this.cardY = (this.height - this.cardH) / 2.0F + (1.0F - ease) * 10.0F;

        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        RenderUtil.drawRect(0, 0, this.width, this.height, alpha(0x05060A, 0.62F * ease));
        RenderUtil.drawRoundedRect(this.cardX - 2.0F, this.cardY - 1.0F, this.cardW + 4.0F, this.cardH + 4.0F, 12.0F,
                alpha(0x000000, 0.30F * ease));
        RenderUtil.drawRoundedRect(this.cardX, this.cardY, this.cardW, this.cardH, 10.0F, alpha(0x11131A, 0.97F * ease));
        RenderUtil.drawRoundedRectOutline(this.cardX, this.cardY, this.cardW, this.cardH, 10.0F, 1.0F,
                alpha(0xFFFFFF, 0.08F * ease), true, true, true, true);
        RenderUtil.drawRoundedRect(this.cardX + pad, this.cardY + 9.0F, 26.0F, 2.0F, 1.0F, alpha(ACCENT, ease));

        float x = this.cardX + pad;
        float y = this.cardY + pad;
        text(title, this.step == 0 ? TITLE : NAME_TITLE, x, y, alpha(0xF5F6FA, ease), ttf);
        y += titleH + 6.0F;
        for (String line : intro) {
            text(body, line, x, y, alpha(0xB4BACB, ease), ttf);
            y += lineH;
        }
        if (this.step == 0) {
            y += 8.0F;
            for (List<String> lines : sent) {
                RenderUtil.drawRoundedRect(x + 1.0F, y + lineH / 2.0F - 2.5F, 3.0F, 3.0F, 1.5F, alpha(ACCENT, ease));
                for (String line : lines) {
                    text(body, line, x + 10.0F, y, alpha(0xE6E8F0, ease), ttf);
                    y += lineH;
                }
            }
            y += 8.0F;
            for (String line : outro) {
                text(body, line, x, y, alpha(0x8A91A6, ease), ttf);
                y += lineH;
            }
        } else {
            y += 10.0F;
            this.fieldY = y;
            boolean ok = UsageReporter.validAlias(this.name.getText());
            RenderUtil.drawRoundedRect(x, y, textW, FIELD_H, 6.0F, alpha(0x000000, 0.35F * ease));
            RenderUtil.drawRoundedRectOutline(x, y, textW, FIELD_H, 6.0F, 1.0F,
                    alpha(ok ? ACCENT : 0xF87171, 0.55F * ease), true, true, true, true);
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            this.field((int) (textW - 14.0F));
            this.name.xPosition = (int) (x + 7.0F);
            this.name.yPosition = (int) (y + (FIELD_H - 8.0F) / 2.0F);
            this.name.drawTextBox();
            GlStateManager.disableAlpha();
            GlStateManager.enableBlend();
            y += FIELD_H + 4.0F;
            String note = !this.problem.isEmpty() ? this.problem
                    : ok ? "Shown on the site as \"" + this.name.getText().trim() + "\"" : "2-20 letters, digits, spaces, _ - or .";
            text(body, note, x, y, alpha(!this.problem.isEmpty() || !ok ? 0xF87171 : 0x8A91A6, ease), ttf);
        }

        this.buttonY = this.cardY + this.cardH - pad - BUTTON_H;
        this.buttonW = (this.cardW - pad * 2.0F - 8.0F) / 2.0F;
        this.noX = this.cardX + pad;
        this.yesX = this.noX + this.buttonW + 8.0F;
        boolean overNo = over(mouseX, mouseY, this.noX);
        boolean overYes = over(mouseX, mouseY, this.yesX);
        RenderUtil.drawRoundedRect(this.noX, this.buttonY, this.buttonW, BUTTON_H, 6.0F,
                alpha(0xFFFFFF, (overNo ? 0.12F : 0.06F) * ease));
        RenderUtil.drawRoundedRectOutline(this.noX, this.buttonY, this.buttonW, BUTTON_H, 6.0F, 1.0F,
                alpha(0xFFFFFF, 0.10F * ease), true, true, true, true);
        RenderUtil.drawRoundedRect(this.yesX, this.buttonY, this.buttonW, BUTTON_H, 6.0F,
                alpha(ACCENT, (overYes ? 1.0F : 0.85F) * ease));
        centered(body, this.step == 0 ? "Don't share" : "Keep suggested", this.noX + this.buttonW / 2.0F, this.buttonY,
                alpha(0xE6E8F0, ease), ttf);
        centered(body, this.step == 0 ? "Agree" : "Use this name", this.yesX + this.buttonW / 2.0F, this.buttonY,
                alpha(0xFFFFFF, ease), ttf);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
    }

    private boolean over(int mx, int my, float bx) {
        return mx >= bx && mx <= bx + this.buttonW && my >= this.buttonY && my <= this.buttonY + BUTTON_H;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 0) {
            return;
        }
        if (this.step == 1) {
            this.name.mouseClicked(mouseX, mouseY, button);
            if (mouseY >= this.fieldY && mouseY <= this.fieldY + FIELD_H) {
                this.name.setFocused(true);
            }
        }
        if (over(mouseX, mouseY, this.yesX)) {
            this.yes();
        } else if (over(mouseX, mouseY, this.noX)) {
            this.no();
        }
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            if (this.step == 1) {
                this.no();
            } else {
                this.mc.displayGuiScreen(null);
            }
            return;
        }
        if (this.step == 1) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                this.yes();
                return;
            }
            this.problem = "";
            this.name.textboxKeyTyped(typed, key);
        }
    }

    private void yes() {
        if (this.step == 0) {
            UsageReporter.setConsent(true);
            this.step = 1;
            this.openedAt = System.currentTimeMillis();
            this.name.setFocused(true);
            this.name.setCursorPositionEnd();
            return;
        }
        if (!UsageReporter.setAlias(this.name.getText())) {
            this.problem = "That name cannot be shown. Try another.";
            return;
        }
        this.done();
    }

    private void no() {
        if (this.step == 0) {
            UsageReporter.setConsent(false);
        }

        this.done();
    }

    private void done() {
        if (this.after != null) {
            this.after.run();
        } else {
            this.mc.displayGuiScreen(null);
        }
    }

    private static List<String> wrap(String[] paragraphs, float width, FontRenderer font, boolean ttf) {
        List<String> out = new ArrayList<String>();
        for (String p : paragraphs) {
            StringBuilder line = new StringBuilder();
            for (String word : p.split(" ")) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && width(font, next, ttf) > width) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            if (line.length() > 0) {
                out.add(line.toString());
            }
        }
        return out;
    }

    private static double width(FontRenderer font, String s, boolean ttf) {
        return ttf ? font.getStringWidth(s) : net.minecraft.client.Minecraft.getMinecraft().fontRendererObj.getStringWidth(s);
    }

    private static void text(FontRenderer font, String s, float x, float y, int colour, boolean ttf) {
        if (ttf) {
            font.drawString(s, x, y, colour, false);
        } else {
            net.minecraft.client.Minecraft.getMinecraft().fontRendererObj.drawString(s, (int) x, (int) y, colour);
        }
    }

    private static void centered(FontRenderer font, String s, float cx, float by, int colour, boolean ttf) {
        double w = width(font, s, ttf);
        double h = ttf ? font.getHeight() : 8.0;
        text(font, s, (float) (cx - w / 2.0), (float) (by + (BUTTON_H - h) / 2.0 + (ttf ? 1.0 : 0.0)), colour, ttf);
    }

    private static int alpha(int rgb, float a) {
        return (Math.max(8, Math.min(255, Math.round(a * 255.0F))) << 24) | (rgb & 0xFFFFFF);
    }
}
