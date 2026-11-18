package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.Render3DEvent;
import atlas.events.TickEvent;
import atlas.mixin.IAccessorRenderManager;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.RenderUtil;
import atlas.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class TargetOrbit extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int ACCENT = 0xFFB59CFF;
    private static final int LOW_HEALTH = 0xFFFF4D5E;
    private static final float FADE_SECONDS = 0.18F;
    private static final float FLASH_SECONDS = 0.35F;
    private static final float APPEAR_SECONDS = 0.45F;
    private static final float STAGGER_SECONDS = 0.012F;
    private static final float BURST_SECONDS = 0.9F;

    private static final float SPREAD = 0.62F;

    public final ModeProperty style = new ModeProperty("pattern", 0,
            new String[]{"Crown", "Wings", "Helix", "Shards", "Orbit", "Halo", "Mark", "Sigil", "Scan", "Lock"});
    public final ModeProperty colorMode = new ModeProperty("color", 0, new String[]{"Theme", "Health", "Custom"});

    public final ColorProperty customColor = new ColorProperty("custom-color", ACCENT & 0xFFFFFF,
            () -> this.colorMode.getValue() == 2);

    public final ColorProperty coreColor = new ColorProperty("core-color", 0xFFFFFF);
    public final FloatProperty size = new FloatProperty("size", 1.0F, 0.6F, 1.6F);
    public final FloatProperty speed = new FloatProperty("speed", 1.0F, 0.2F, 3.0F);
    public final IntProperty crystals = new IntProperty("crystals", 18, 6, 36, () -> this.style.getValue() <= 3);
    public final FloatProperty crystalSize = new FloatProperty("crystal-size", 1.0F, 0.5F, 2.0F,
            () -> this.style.getValue() <= 3);
    public final IntProperty comets = new IntProperty("comets", 3, 0, 5, () -> this.style.getValue() == 4);
    public final IntProperty trail = new IntProperty("trail", 14, 4, 30, () -> this.style.getValue() == 4);
    public final FloatProperty opacity = new FloatProperty("opacity", 0.9F, 0.2F, 1.0F);

    public final BooleanProperty bodyGlow = new BooleanProperty("body-glow", false);
    public final ColorProperty glowTint = new ColorProperty("glow-color", ACCENT & 0xFFFFFF, () -> this.bodyGlow.getValue());
    public final BooleanProperty glowFollows = new BooleanProperty("glow-follows-mark", true, () -> this.bodyGlow.getValue());

    public final BooleanProperty hitRings = new BooleanProperty("hit-rings", false);
    public final FloatProperty glowStrength = new FloatProperty("glow-strength", 0.5F, 0.1F, 1.0F,
            () -> this.bodyGlow.getValue());
    public final FloatProperty combatRange = new FloatProperty("combat-range", 6.0F, 3.0F, 12.0F);
    public final FloatProperty combatTime = new FloatProperty("combat-time", 4.0F, 1.0F, 10.0F);

    public final BooleanProperty preview = new BooleanProperty("preview", false);

    public static final class Fx {
        public float alpha;
        public float shownFor;
        public float flash;
        public double clock;

        public double spin;

        public float health = 1.0F;
        int lastHurt;

        public void reset() {
            reset(0);
        }

        void reset(int hurt) {
            this.alpha = 0.0F;
            this.shownFor = 0.0F;
            this.flash = 0.0F;
            this.lastHurt = hurt;
            this.health = 1.0F;
        }
    }

    public static final class Burst {
        double ox, oy, oz;
        final List<float[]> parts = new ArrayList<float[]>();
        float age;
        int color;
    }

    private EntityLivingBase shown;
    private final Fx shownFx = new Fx();
    private EntityLivingBase leaving;
    private final Fx leavingFx = new Fx();
    private final List<Burst> bursts = new ArrayList<Burst>();

    private EntityLivingBase lastHit;
    private long lastHitAt;
    private long lastNanos;

    private static int core = 0xFFFFFFFF;

    private int core() {
        return 0xFF000000 | this.coreColor.getValue();
    }

    private double now;

    private double spinNow;

    private float viewYaw;
    private float viewPitch;

    private float formationYaw;

    private static Entity glowEntity;
    private static int glowColor = ACCENT;
    private static float glowAmount;

    private static final int MAX = 36;
    private static final float[][] SEED = new float[MAX][];

    static {
        Random random = new Random(0xA71A5L);
        for (int i = 0; i < MAX; i++) {
            float angle = (float) (i * 2.399963);
            float radius = 0.85F + random.nextFloat() * 0.45F;
            float height = 0.05F + random.nextFloat() * 1.05F;
            float speed = (0.35F + random.nextFloat() * 0.55F) * (random.nextBoolean() ? 1.0F : -1.0F);
            float bob = 0.05F + random.nextFloat() * 0.12F;
            float phase = random.nextFloat() * 6.2831855F;
            float size = 0.75F + random.nextFloat() * 0.6F;
            float spin = (random.nextFloat() - 0.5F) * 160.0F;
            SEED[i] = new float[]{angle, radius, height, speed, bob, phase, size, spin};
        }
    }

    public TargetOrbit() {
        super("TargetOrbit", true, false, "Marks who you are fighting with glowing crystal formations and a body glow");
    }

    public static TargetOrbit instance() {
        try {
            Module module = atlas.Atlas.moduleManager.modules.get(TargetOrbit.class);
            return module instanceof TargetOrbit ? (TargetOrbit) module : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean active() {
        TargetOrbit module = instance();
        return module != null && module.isEnabled();
    }

    static int healthColor(int base, float ratio) {
        float r = Math.max(0.0F, Math.min(1.0F, ratio));
        return mix(base, LOW_HEALTH, r >= 0.5F ? 0.0F : 1.0F - r / 0.5F);
    }

    static int mix(int a, int b, float t) {
        float u = Math.max(0.0F, Math.min(1.0F, t));
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * u);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * u);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * u);
        return (a & 0xFF000000) | (r << 16) | (g << 8) | bl;
    }

    static float easeOutBack(float t) {
        float x = Math.max(0.0F, Math.min(1.0F, t)) - 1.0F;
        float c1 = 1.70158F;
        return 1.0F + (c1 + 1.0F) * x * x * x + c1 * x * x;
    }

    public int colorFor(EntityLivingBase entity) {
        int base = ACCENT;
        if (this.colorMode.getValue() == 2) {
            return 0xFF000000 | this.customColor.getValue();
        }
        ThemeStyle themed = ThemeStyle.active(TargetColors.class);
        if (themed != null) {
            base = themed.color(new Color(ACCENT, true), entity, -1).getRGB();
        }
        if (this.colorMode.getValue() == 1 && entity != null) {
            float max = entity.getMaxHealth();
            base = healthColor(base, max <= 0.0F ? 1.0F : entity.getHealth() / max);
        }
        return base;
    }

    @EventTarget
    public void onAttack(atlas.events.AttackEvent event) {
        if (this.isEnabled() && event.getTarget() instanceof EntityLivingBase && event.getTarget() != mc.thePlayer) {
            this.lastHit = (EntityLivingBase) event.getTarget();
            this.lastHitAt = System.currentTimeMillis();
        }
    }

    private EntityLivingBase fighting() {
        EntityLivingBase hit = this.lastHit;
        if (hit == null || hit.worldObj != mc.theWorld
                || System.currentTimeMillis() - this.lastHitAt > this.combatTime.getValue() * 1000.0F) {
            return null;
        }
        float range = this.combatRange.getValue();
        return mc.thePlayer.getDistanceSqToEntity(hit) <= range * range ? hit : null;
    }

    private static EntityLivingBase nearestPlayer() {
        EntityLivingBase best = null;
        double bestSq = 64.0;
        for (net.minecraft.entity.player.EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.isDead || player.isInvisible()) {
                continue;
            }
            double d = mc.thePlayer.getDistanceSqToEntity(player);
            if (d < bestSq) {
                bestSq = d;
                best = player;
            }
        }
        return best;
    }

    private static boolean dying(EntityLivingBase entity) {
        return entity.isDead || entity.getHealth() <= 0.0F || entity.deathTime > 0;
    }

    @EventTarget(whenDisabled = true)
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.PRE && !this.isEnabled() && mc.thePlayer != null
                && atlas.util.Auras.anyEnabled()) {
            this.setEnabled(true);
        }
    }

    @Override
    public void onDisabled() {
        glowEntity = null;
        this.shown = null;
        this.leaving = null;
        this.bursts.clear();
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null || mc.getRenderManager() == null) {
            glowEntity = null;
            return;
        }
        long nanos = System.nanoTime();
        float dt = this.lastNanos == 0L ? 0.016F : Math.min(0.1F, (nanos - this.lastNanos) / 1.0E9F);
        this.lastNanos = nanos;
        float partial = event.getPartialTicks();

        EntityLivingBase target = atlas.util.Auras.anyEnabled() ? atlas.util.Auras.target() : null;
        if (target == null) {
            target = fighting();
        }
        if (target == null && this.preview.getValue()) {
            target = nearestPlayer();
        }
        if (target != null && !TeamUtil.isEntityLoaded(target)) {
            target = null;
        }

        if (this.shown != null && dying(this.shown)) {
            shatter(this.shown, this.shownFx, partial);
            if (this.lastHit == this.shown) {
                this.lastHit = null;
            }
            this.shown = null;
        }
        if (target != null && dying(target)) {
            target = null;
        }
        if (target != this.shown) {
            if (this.shown != null && this.shownFx.alpha > 0.05F) {
                this.leaving = this.shown;
                this.leavingFx.alpha = this.shownFx.alpha;
                this.leavingFx.shownFor = this.shownFx.shownFor;
                this.leavingFx.clock = this.shownFx.clock;
                this.leavingFx.spin = this.shownFx.spin;
                this.leavingFx.health = this.shownFx.health;
                this.leavingFx.flash = 0.0F;
            }
            this.shown = target;
            this.shownFx.reset(target == null ? 0 : target.hurtTime);
        }
        if (this.shown != null) {
            step(this.shownFx, this.shown, dt);
        }
        if (this.leaving != null) {
            this.leavingFx.alpha -= dt / FADE_SECONDS;
            this.leavingFx.clock += dt * this.speed.getValue();
            this.leavingFx.spin += dt * this.speed.getValue();
            if (this.leavingFx.alpha <= 0.0F || this.leaving.isDead) {
                this.leaving = null;
            }
        }
        if (this.bodyGlow.getValue() && this.shown != null) {
            glowEntity = this.shown;
            glowColor = glowColorFor(colorFor(this.shown), this.shownFx);
            glowAmount = glowAmountFor(this.shownFx);
        } else {
            glowEntity = null;
        }

        IAccessorRenderManager rm = (IAccessorRenderManager) mc.getRenderManager();
        setView(mc.getRenderManager().playerViewY,
                mc.getRenderManager().playerViewX * (mc.gameSettings.thirdPersonView == 2 ? -1.0F : 1.0F));
        begin();
        try {
            if (this.leaving != null) {
                worldDraw(this.leaving, this.leavingFx, partial, rm);
            }
            if (this.shown != null) {
                worldDraw(this.shown, this.shownFx, partial, rm);
            }
            drawBursts(this.bursts, dt, rm.getRenderPosX(), rm.getRenderPosY(), rm.getRenderPosZ());
        } finally {
            end();
        }
    }

    public void step(Fx fx, EntityLivingBase entity, float dt) {
        fx.clock += dt * this.speed.getValue();
        fx.shownFor += dt;
        fx.alpha = Math.min(1.0F, fx.alpha + dt / FADE_SECONDS);
        if (entity != null) {
            if (entity.hurtTime > fx.lastHurt) {
                fx.flash = 1.0F;
            }
            fx.lastHurt = entity.hurtTime;
        }
        fx.flash = Math.max(0.0F, fx.flash - dt / FLASH_SECONDS);
        fx.spin += dt * this.speed.getValue() * (1.0F + 3.0F * fx.flash);
        if (entity != null) {
            float max = entity.getMaxHealth();
            fx.health = max <= 0.0F ? 1.0F : Math.max(0.0F, Math.min(1.0F, entity.getHealth() / max));
        }
    }

    public int glowColorFor(int base, Fx fx) {
        int own = this.glowFollows.getValue() ? base : 0xFF000000 | this.glowTint.getValue();
        return mix(own, core(), fx.flash * 0.5F);
    }

    public float glowAmountFor(Fx fx) {
        return fx.alpha * this.glowStrength.getValue()
                * (0.8F + 0.2F * (float) Math.sin(fx.clock * 2.4)) * (1.0F + fx.flash);
    }

    private void worldDraw(EntityLivingBase entity, Fx fx, float partial, IAccessorRenderManager rm) {
        double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partial - rm.getRenderPosX();
        double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partial - rm.getRenderPosY();
        double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partial - rm.getRenderPosZ();
        float bodyYaw = entity.prevRenderYawOffset + (entity.renderYawOffset - entity.prevRenderYawOffset) * partial;
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        renderEffect(entity.width, entity.height, colorFor(entity), fx, bodyYaw);
        GL11.glPopMatrix();
    }

    public void setView(float yaw, float pitch) {
        this.viewYaw = yaw;
        this.viewPitch = pitch;
    }

    public static void begin() {
        RenderUtil.enableRenderState();
        GlStateManager.disableLighting();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
    }

    public static void end() {
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.depthMask(true);
        GlStateManager.shadeModel(GL11.GL_FLAT);
        RenderUtil.disableRenderState();

        GlStateManager.resetColor();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public void renderEffect(float width, float height, int baseColor, Fx fx, float bodyYaw) {
        float alpha = fx.alpha * this.opacity.getValue();
        if (alpha <= 0.01F) {
            return;
        }
        this.now = fx.clock;
        this.spinNow = fx.spin;
        core = core();
        int color = mix(baseColor, core, fx.flash * 0.55F);
        int look = this.style.getValue();
        if (look > 3) {
            float scale = this.size.getValue() * (1.0F - 0.12F * fx.flash);
            switch (look) {
                case 5:
                    drawHalo(width, color, alpha, scale);
                    break;
                case 6:
                    drawMark(height, color, alpha, scale);
                    break;
                case 7:
                    drawSigil(width, color, alpha, fx, bodyYaw);
                    break;
                case 8:
                    drawScan(width, height, color, alpha, fx);
                    break;
                case 9:
                    drawLock(width, height, color, baseColor, alpha, fx);
                    break;
                default:
                    drawOrbit(width, height, color, alpha, scale);
                    break;
            }
            return;
        }
        float body = (width * 0.5F + SPREAD) * this.size.getValue();
        if (fx.flash > 0.0F && this.hitRings.getValue()) {
            float wave = 1.0F - fx.flash;
            band(body * (0.6F + wave * 1.9F), 0.035F * fx.flash + 0.01F, color, alpha * fx.flash * 0.85F, 0.02F);
        }
        GL11.glPushMatrix();
        GL11.glRotatef(-bodyYaw, 0.0F, 1.0F, 0.0F);
        this.formationYaw = bodyYaw;
        List<float[]> placed = formation(look, width, height, fx.flash);
        float unitBase = 0.1F * this.crystalSize.getValue() * this.size.getValue();
        for (int i = 0; i < placed.size(); i++) {
            float[] p = placed.get(i);

            float appear = Math.max(0.0F, Math.min(1.0F, (fx.shownFor - i * STAGGER_SECONDS) / APPEAR_SECONDS));
            if (appear <= 0.0F) {
                continue;
            }
            float e = easeOutBack(appear);
            float[] s = SEED[i % MAX];
            double fromX = Math.cos(s[0]) * body * 2.6;
            double fromZ = Math.sin(s[0]) * body * 2.6;
            double fromY = height * s[2] + 0.7;
            float glint = 0.72F + 0.28F * (float) Math.sin(this.now * 3.3 + s[5] * 2.0);
            GL11.glPushMatrix();
            GL11.glTranslated(fromX + (p[0] - fromX) * e, fromY + (p[1] - fromY) * e, fromZ + (p[2] - fromZ) * e);
            GL11.glRotatef(this.formationYaw, 0.0F, 1.0F, 0.0F);
            flake(unitBase * p[7], color, alpha * Math.min(1.0F, appear * 2.5F) * glint * p[8],
                    p[5] + p[6] + (1.0F - e) * 240.0F);
            GL11.glPopMatrix();
        }
        GL11.glPopMatrix();
    }

    List<float[]> formation(int look, float width, float height, float flash) {
        List<float[]> out = new ArrayList<float[]>();
        float size = this.size.getValue();
        double t = this.now;
        float swell = 1.0F + 0.22F * flash + 0.04F * (float) Math.sin(t * 1.4);
        float body = (width * 0.5F + SPREAD) * size * swell;
        double sp = this.spinNow;
        int count = Math.min(MAX, this.crystals.getValue());
        switch (look) {
            case 1: {
                int perWing = Math.max(3, Math.min(9, count / 2));
                float beat = (float) Math.sin(t * 2.2) * 10.0F + 26.0F * flash;
                for (int side = -1; side <= 1; side += 2) {
                    for (int j = 0; j < perWing; j++) {
                        float share = j / (float) (perWing - 1);
                        float fan = 18.0F + share * 125.0F + beat * share;
                        double rad = Math.toRadians(fan);
                        float reach = (0.3F + j * 0.13F) * size * swell;
                        double x = side * (0.16 * size + Math.sin(rad) * reach);
                        double y = height * 0.74 + Math.cos(rad) * reach;
                        double z = -(width * 0.5 + 0.1) * size - share * 0.08;
                        out.add(new float[]{(float) x, (float) y, (float) z, 0.0F, -14.0F, -side * fan,
                                0.0F, 1.05F + (1.0F - share) * 0.5F, 1.0F});
                    }
                }
                break;
            }
            case 2: {
                int perStrand = Math.max(3, count / 2);
                for (int strand = 0; strand < 2; strand++) {
                    for (int k = 0; k < perStrand; k++) {
                        float u = k / (float) (perStrand - 1);
                        double angle = Math.PI / 2.0 + strand * Math.PI + u * Math.PI * 2.4 + sp * 1.4;
                        out.add(new float[]{(float) (Math.cos(angle) * body), height * (0.04F + u * 1.02F),
                                (float) (Math.sin(angle) * body), (float) -Math.toDegrees(angle), 62.0F, 0.0F,
                                0.0F, 0.75F + 0.45F * (1.0F - u), 1.0F});
                    }
                }
                break;
            }
            case 3: {
                for (int i = 0; i < count; i++) {
                    float[] s = SEED[i];
                    double angle = s[0] + s[3] * sp;
                    double r = body * s[1];
                    double y = height * s[2] + Math.sin(t * 1.7 + s[5]) * s[4];
                    out.add(new float[]{(float) (Math.cos(angle) * r), (float) y, (float) (Math.sin(angle) * r),
                            (float) -Math.toDegrees(angle), 20.0F, 0.0F, (float) (s[7] * t), s[6], 1.0F});
                }
                break;
            }
            default: {
                int perRing = Math.max(3, count / 3);
                float[] heights = {0.3F, 0.62F, 0.96F};
                float[] widths = {1.05F, 0.96F, 0.8F};
                for (int ring = 0; ring < 3; ring++) {
                    double turn = (ring == 1 ? -1.0 : 1.0) * sp * 0.45 + ring * Math.PI / perRing;
                    for (int k = 0; k < perRing; k++) {
                        double angle = k * Math.PI * 2.0 / perRing + turn;
                        double r = body * widths[ring];
                        out.add(new float[]{(float) (Math.cos(angle) * r), height * heights[ring],
                                (float) (Math.sin(angle) * r), (float) (90.0 - Math.toDegrees(angle)), 14.0F, 0.0F,
                                0.0F, 0.9F, 0.9F});
                    }
                }

                float bob = (float) Math.sin(t * 2.0) * 0.04F;
                out.add(new float[]{0.0F, height * 0.84F + bob, body * 1.3F, 0.0F, 90.0F, 0.0F,
                        0.0F, 1.6F, 1.25F});
                break;
            }
        }
        return out;
    }

    private void shatter(EntityLivingBase entity, Fx fx, float partial) {
        if (this.style.getValue() > 3 || fx.alpha <= 0.05F) {
            return;
        }
        Burst burst = new Burst();
        burst.ox = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partial;
        burst.oy = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partial;
        burst.oz = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partial;
        burst.color = colorFor(entity);
        this.now = fx.clock;
        this.spinNow = fx.spin;
        addBurstParts(burst, entity.width, entity.height, entity.renderYawOffset);
        this.bursts.add(burst);
    }

    public void shatterAt(List<Burst> into, float width, float height, int color, Fx fx, float bodyYaw) {
        if (this.style.getValue() > 3) {
            return;
        }
        Burst burst = new Burst();
        burst.color = color;
        this.now = fx.clock;
        this.spinNow = fx.spin;
        addBurstParts(burst, width, height, bodyYaw);
        into.add(burst);
    }

    private void addBurstParts(Burst burst, float width, float height, float bodyYaw) {
        double yaw = Math.toRadians(-bodyYaw);
        Random random = new Random();
        for (float[] p : formation(this.style.getValue(), width, height, 0.0F)) {
            double x = p[0] * Math.cos(yaw) + p[2] * Math.sin(yaw);
            double z = -p[0] * Math.sin(yaw) + p[2] * Math.cos(yaw);
            double len = Math.max(0.2, Math.sqrt(x * x + z * z));
            float out = 1.8F + random.nextFloat() * 2.4F;
            burst.parts.add(new float[]{(float) x, p[1], (float) z, (float) (x / len * out),
                    1.8F + random.nextFloat() * 2.2F, (float) (z / len * out), random.nextFloat() * 360.0F, p[7]});
        }
    }

    public void drawBursts(List<Burst> list, float dt, double camX, double camY, double camZ) {
        Iterator<Burst> it = list.iterator();
        float unitBase = 0.1F * this.crystalSize.getValue() * this.size.getValue();
        core = core();
        while (it.hasNext()) {
            Burst burst = it.next();
            burst.age += dt;
            if (burst.age >= BURST_SECONDS) {
                it.remove();
                continue;
            }
            float life = 1.0F - burst.age / BURST_SECONDS;
            float t = burst.age;
            for (float[] part : burst.parts) {
                GL11.glPushMatrix();
                GL11.glTranslated(burst.ox - camX + part[0] + part[3] * t,
                        burst.oy - camY + part[1] + part[4] * t - 5.0 * t * t,
                        burst.oz - camZ + part[2] + part[5] * t);
                flake(unitBase * part[7] * (0.4F + 0.6F * life), burst.color, life * this.opacity.getValue(),
                        part[6] + t * 540.0F);
                GL11.glPopMatrix();
            }
        }
    }

    public static void glowModel(EntityLivingBase entity, ModelBase model, float limbSwing, float limbSwingAmount,
                                 float ageInTicks, float netHeadYaw, float headPitch, float scale) {
        if (model == null || entity == null || entity != glowEntity || entity.isInvisible() || glowAmount <= 0.01F) {
            return;
        }
        boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        GlStateManager.enableCull();
        GlStateManager.cullFace(mirrored() ? GL11.GL_BACK : GL11.GL_FRONT);
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        mc.entityRenderer.disableLightmap();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GlStateManager.depthMask(false);
        float r = ((glowColor >> 16) & 0xFF) / 255.0F;
        float g = ((glowColor >> 8) & 0xFF) / 255.0F;
        float b = (glowColor & 0xFF) / 255.0F;
        float[][] layers = {{1.06F, 0.6F}, {1.12F, 0.28F}};
        try {
            for (float[] layer : layers) {
                GlStateManager.pushMatrix();

                GlStateManager.translate(0.0F, 0.75F, 0.0F);
                GlStateManager.scale(layer[0], layer[0], layer[0]);
                GlStateManager.translate(0.0F, -0.75F, 0.0F);
                GlStateManager.color(r, g, b, Math.min(1.0F, glowAmount * layer[1]));
                model.render(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, scale);
                GlStateManager.popMatrix();
            }
        } finally {
            GlStateManager.cullFace(GL11.GL_BACK);
            if (!cull) {
                GlStateManager.disableCull();
            }
            GlStateManager.depthMask(true);
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            if (!blend) {
                GlStateManager.disableBlend();
            }
            mc.entityRenderer.enableLightmap();
            if (lighting) {
                GlStateManager.enableLighting();
            }
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private static final java.nio.FloatBuffer MATRIX = org.lwjgl.BufferUtils.createFloatBuffer(16);

    private static boolean mirrored() {
        return det3(GL11.GL_MODELVIEW_MATRIX) * det3(GL11.GL_PROJECTION_MATRIX) > 0.0F;
    }

    private static float det3(int which) {
        MATRIX.clear();
        GL11.glGetFloat(which, MATRIX);
        float a = MATRIX.get(0), b = MATRIX.get(4), c = MATRIX.get(8);
        float d = MATRIX.get(1), e = MATRIX.get(5), f = MATRIX.get(9);
        float g = MATRIX.get(2), h = MATRIX.get(6), i = MATRIX.get(10);
        return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    }

    public static void withGlow(Entity entity, int color, float amount, Runnable drawing) {
        Entity savedEntity = glowEntity;
        int savedColor = glowColor;
        float savedAmount = glowAmount;
        glowEntity = entity;
        glowColor = color;
        glowAmount = amount;
        try {
            drawing.run();
        } finally {
            glowEntity = savedEntity;
            glowColor = savedColor;
            glowAmount = savedAmount;
        }
    }

    private void flake(float w, int color, float alpha, float spin) {
        if (alpha <= 0.01F || w <= 0.0F) {
            return;
        }
        GL11.glRotatef(-this.viewYaw, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(this.viewPitch, 1.0F, 0.0F, 0.0F);
        softGlow(w * 4.0F, color, alpha * 0.22F);
        GL11.glRotatef(spin, 0.0F, 0.0F, 1.0F);
        diamond(w * 2.6F, color, alpha * 0.45F, color, 0.0F);
        diamond(w, core, alpha, color, alpha * 0.85F);
    }

    private static void softGlow(float r, int color, float alpha) {
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        colour(color, alpha);
        GL11.glVertex3f(0.0F, 0.0F, 0.0F);
        colour(color, 0.0F);
        for (int i = 0; i <= 20; i++) {
            double a = i * Math.PI * 2.0 / 20;
            GL11.glVertex3f((float) Math.cos(a) * r, (float) Math.sin(a) * r, 0.0F);
        }
        GL11.glEnd();
    }

    private static void diamond(float w, int centre, float centreAlpha, int rim, float rimAlpha) {
        float h = w * 1.7F;
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        colour(centre, centreAlpha);
        GL11.glVertex3f(0.0F, 0.0F, 0.0F);
        colour(rim, rimAlpha);
        GL11.glVertex3f(0.0F, h, 0.0F);
        GL11.glVertex3f(-w, 0.0F, 0.0F);
        GL11.glVertex3f(0.0F, -h, 0.0F);
        GL11.glVertex3f(w, 0.0F, 0.0F);
        GL11.glVertex3f(0.0F, h, 0.0F);
        GL11.glEnd();
    }

    private float appearOf(Fx fx) {
        return Math.max(0.0F, Math.min(1.0F, fx.shownFor / APPEAR_SECONDS));
    }

    private void drawSigil(float width, int color, float alpha, Fx fx, float bodyYaw) {
        float appear = appearOf(fx);
        float r = (width * 0.5F + 0.85F) * this.size.getValue() * easeOutBack(appear) * (1.0F + 0.06F * fx.flash);
        if (r <= 0.02F) {
            return;
        }
        float a = alpha * Math.min(1.0F, appear * 2.0F);
        double sp = this.spinNow;
        GL11.glPushMatrix();
        GL11.glTranslated(0.0, 0.03, 0.0);
        GL11.glRotatef(-bodyYaw, 0.0F, 1.0F, 0.0F);

        GL11.glRotatef(90.0F, 1.0F, 0.0F, 0.0F);
        fan2(r * 1.2F, color, a * 0.12F);
        arc2(r, 0.03F, 0.0, Math.PI * 2.0, color, a);
        arc2(r * 0.8F, 0.02F, 0.0, Math.PI * 2.0, color, a * 0.8F);

        for (int i = 0; i < 24; i++) {
            double angle = sp * 0.3 + i * Math.PI * 2.0 / 24;
            float c = (float) Math.cos(angle);
            float sn = (float) Math.sin(angle);
            float inner = r * 0.84F;
            float outer = r * (0.86F + 0.1F * (SEED[i][2] / 1.1F));
            float flicker = 0.6F + 0.4F * (float) Math.sin(this.now * 4.0 + SEED[i][5]) * fx.flash;
            line2(c * inner, sn * inner, c * outer, sn * outer, 0.012F, color, a * 0.85F * (0.6F + 0.4F * flicker));
        }

        for (int tri = 0; tri < 2; tri++) {
            double rot = (tri == 0 ? 1.0 : -1.0) * sp * 0.2 + Math.PI / 2.0 + tri * Math.PI;
            float rr = r * 0.74F;
            for (int k = 0; k < 3; k++) {
                double a1 = rot + k * Math.PI * 2.0 / 3.0;
                double a2 = rot + (k + 1) * Math.PI * 2.0 / 3.0;
                line2((float) Math.cos(a1) * rr, (float) Math.sin(a1) * rr, (float) Math.cos(a2) * rr,
                        (float) Math.sin(a2) * rr, 0.016F, color, a * 0.75F);
            }
        }

        for (int i = 0; i < 12; i++) {
            double a0 = -sp * 0.5 + i * Math.PI * 2.0 / 12;
            arc2(r * 0.3F, 0.014F, a0, a0 + Math.PI / 12.0, color, a * 0.7F);
        }

        float tip = r * 1.22F;
        float base = r * 1.06F;
        GL11.glBegin(GL11.GL_TRIANGLES);
        colour(core, a);
        GL11.glVertex3f(0.0F, tip, 0.0F);
        colour(color, a * 0.8F);
        GL11.glVertex3f(-0.09F * this.size.getValue(), base, 0.0F);
        GL11.glVertex3f(0.09F * this.size.getValue(), base, 0.0F);
        GL11.glEnd();
        fan2At(0.0F, (tip + base) / 2.0F, 0.18F * this.size.getValue(), color, a * 0.35F);
        if (fx.flash > 0.0F && this.hitRings.getValue()) {
            arc2(r * (1.0F + (1.0F - fx.flash) * 0.9F), 0.035F * fx.flash + 0.008F, 0.0, Math.PI * 2.0,
                    mix(color, core, 0.5F), a * fx.flash);
        }
        GL11.glPopMatrix();
    }

    private void drawScan(float width, float height, int color, float alpha, Fx fx) {
        float appear = appearOf(fx);
        float r = (width * 0.5F + 0.4F) * this.size.getValue() * (1.0F + 0.08F * fx.flash);
        float top = height * 1.08F * Math.max(0.0F, easeOutBack(appear));
        if (top <= 0.02F) {
            return;
        }
        float a = alpha * Math.min(1.0F, appear * 2.0F);
        wall(r, 0.0F, top, color, a * (0.22F + 0.2F * fx.flash), 0.0F);

        for (int i = 0; i < 10; i++) {
            double angle = this.spinNow * 0.3 + i * Math.PI * 2.0 / 10;
            rib(r * 1.005F, angle, 0.012F, top * 0.92F, color, a * 0.45F);
        }

        double phase = this.now * 1.8;
        float y = top * (0.5F + 0.46F * (float) Math.sin(phase));
        float trail = 0.22F * (Math.cos(phase) >= 0.0 ? -1.0F : 1.0F);
        wall(r * 1.01F, y, y + trail, color, a * 0.55F, 0.0F);
        wall(r * 1.01F, y - 0.012F, y + 0.012F, mix(color, core, 0.5F), a, a);
        if (fx.flash > 0.0F && this.hitRings.getValue()) {
            float hy = top * (1.0F - fx.flash);
            wall(r * 1.02F, hy - 0.02F, hy + 0.02F, core, a * fx.flash, a * fx.flash);
            wall(r * 1.02F, hy, hy - 0.3F, color, a * fx.flash * 0.6F, 0.0F);
        }
    }

    private void drawLock(float width, float height, int color, int baseColor, float alpha, Fx fx) {
        float appear = appearOf(fx);
        float e = easeOutBack(appear);
        float a = alpha * Math.min(1.0F, appear * 2.0F);
        float size = this.size.getValue();
        float hw = (width * 0.5F + 0.5F) * size;
        float hh = (height * 0.5F + 0.35F) * size;
        float close = (2.0F - e) * (1.0F - 0.14F * fx.flash) * (1.0F + 0.025F * (float) Math.sin(this.now * 3.0));
        GlStateManager.disableDepth();
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(0.0, height * 0.52, 0.0);
            GL11.glRotatef(-this.viewYaw, 0.0F, 1.0F, 0.0F);
            GL11.glRotatef(this.viewPitch, 1.0F, 0.0F, 0.0F);
            GL11.glRotatef((1.0F - e) * 90.0F, 0.0F, 0.0F, 1.0F);
            float arm = Math.min(hw, hh) * 0.45F;
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    float cx = sx * hw * close;
                    float cy = sy * hh * close;
                    line2(cx, cy, cx - sx * arm, cy, 0.02F, color, a);
                    line2(cx, cy, cx, cy - sy * arm, 0.02F, color, a);
                }
            }
            float ring = hw * 0.95F * close;
            for (int i = 0; i < 12; i++) {
                double a0 = this.spinNow * 1.2 + i * Math.PI * 2.0 / 12;
                arc2(ring, 0.012F, a0, a0 + Math.PI / 10.0, color, a * 0.6F);
            }

            float hr = ring * 1.12F;
            arc2(hr, 0.018F, 0.0, Math.PI * 2.0, color, a * 0.15F);
            if (fx.health > 0.0F) {
                arc2(hr, 0.022F, Math.PI / 2.0, Math.PI / 2.0 + Math.PI * 2.0 * fx.health,
                        healthColor(baseColor, fx.health), a);
            }
        } finally {
            GL11.glPopMatrix();
            GlStateManager.enableDepth();
        }
    }

    private static final float[] ACROSS = {3.0F, 1.0F, 0.0F, -1.0F, -3.0F};
    private static final float[] ACROSS_ALPHA = {0.0F, 0.8F, 1.0F, 0.8F, 0.0F};

    private static void line2(float x1, float y1, float x2, float y2, float half, int color, float alpha) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0E-4F || alpha <= 0.01F) {
            return;
        }
        float nx = -dy / len * half;
        float ny = dx / len * half;
        int mid = mix(color, core, 0.45F);
        for (int row = 0; row < ACROSS.length - 1; row++) {
            GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
            for (int k = row; k <= row + 1; k++) {
                colour(k == 2 ? mid : color, alpha * ACROSS_ALPHA[k]);
                GL11.glVertex3f(x1 + nx * ACROSS[k], y1 + ny * ACROSS[k], 0.0F);
                GL11.glVertex3f(x2 + nx * ACROSS[k], y2 + ny * ACROSS[k], 0.0F);
            }
            GL11.glEnd();
        }
    }

    private static void arc2(float r, float half, double from, double to, int color, float alpha) {
        if (alpha <= 0.01F || to <= from) {
            return;
        }
        int steps = Math.max(3, (int) Math.ceil(64 * (to - from) / (Math.PI * 2.0)));
        int mid = mix(color, core, 0.45F);
        for (int row = 0; row < ACROSS.length - 1; row++) {
            GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
            for (int i = 0; i <= steps; i++) {
                double angle = from + (to - from) * i / steps;
                float c = (float) Math.cos(angle);
                float sn = (float) Math.sin(angle);
                for (int k = row; k <= row + 1; k++) {
                    float rr = r + half * ACROSS[k];
                    colour(k == 2 ? mid : color, alpha * ACROSS_ALPHA[k]);
                    GL11.glVertex3f(c * rr, sn * rr, 0.0F);
                }
            }
            GL11.glEnd();
        }
    }

    private static void fan2(float r, int color, float alpha) {
        fan2At(0.0F, 0.0F, r, color, alpha);
    }

    private static void fan2At(float x, float y, float r, int color, float alpha) {
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        colour(color, alpha);
        GL11.glVertex3f(x, y, 0.0F);
        colour(color, 0.0F);
        for (int i = 0; i <= 32; i++) {
            double angle = i * Math.PI * 2.0 / 32;
            GL11.glVertex3f(x + (float) Math.cos(angle) * r, y + (float) Math.sin(angle) * r, 0.0F);
        }
        GL11.glEnd();
    }

    private static void wall(float r, float y0, float y1, int color, float a0, float a1) {
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = i * Math.PI * 2.0 / SEGMENTS;
            float c = (float) Math.cos(angle);
            float sn = (float) Math.sin(angle);
            colour(color, a0);
            GL11.glVertex3f(c * r, y0, sn * r);
            colour(color, a1);
            GL11.glVertex3f(c * r, y1, sn * r);
        }
        GL11.glEnd();
    }

    private static void rib(float r, double angle, float half, float height, int color, float alpha) {
        double d = half / r;
        float c1 = (float) Math.cos(angle - d);
        float s1 = (float) Math.sin(angle - d);
        float c2 = (float) Math.cos(angle + d);
        float s2 = (float) Math.sin(angle + d);
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        colour(color, alpha);
        GL11.glVertex3f(c1 * r, 0.0F, s1 * r);
        GL11.glVertex3f(c2 * r, 0.0F, s2 * r);
        colour(color, 0.0F);
        GL11.glVertex3f(c1 * r, height, s1 * r);
        GL11.glVertex3f(c2 * r, height, s2 * r);
        GL11.glEnd();
    }

    private void drawOrbit(float width, float height, int color, float alpha, float scale) {
        float radius = (width * 0.5F + 0.55F) * scale;
        GL11.glPushMatrix();
        GL11.glTranslated(0.0, height * 0.55 + Math.sin(this.now * 1.3) * height * 0.18, 0.0);
        GL11.glRotated(this.now * 25.0, 0.0, 1.0, 0.0);
        GL11.glRotated(14.0 * Math.sin(this.now * 0.7), 1.0, 0.0, 0.0);
        glow(radius, 0.13F, color, alpha * 0.22F);
        band(radius, 0.022F, color, alpha * 0.9F, 0.0F);
        int count = this.comets.getValue();
        int length = this.trail.getValue();
        for (int i = 0; i < count; i++) {
            comet(radius, this.now * 2.2 + i * Math.PI * 2.0 / count, length, color, alpha);
        }
        GL11.glPopMatrix();
    }

    private static void comet(float radius, double head, int length, int color, float alpha) {
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int k = 0; k <= length; k++) {
            float fall = 1.0F - k / (float) length;
            double angle = head - k * 0.07;
            double cx = Math.cos(angle) * radius;
            double cz = Math.sin(angle) * radius;
            float width = 0.055F * fall;
            colour(color, alpha * fall * fall);
            GL11.glVertex3d(cx, width, cz);
            GL11.glVertex3d(cx, -width, cz);
        }
        GL11.glEnd();
        double hx = Math.cos(head) * radius;
        double hz = Math.sin(head) * radius;
        disc(hx, 0.0, hz, 0.07F, color, alpha);
        disc(hx, 0.0, hz, 0.032F, core, alpha);
    }

    private void drawHalo(float width, int color, float alpha, float scale) {
        float radius = (width * 0.5F + 0.5F) * scale * (1.0F + 0.05F * (float) Math.sin(this.now * 3.0));
        glow(radius, 0.18F, color, alpha * 0.28F);
        band(radius, 0.03F, color, alpha * 0.95F, 0.03F);
        for (int i = 0; i < 2; i++) {
            double phase = (this.now * 0.6 + i * 0.5) % 1.0;
            band(radius * (float) (1.0 + phase * 0.7), 0.012F, color, alpha * (float) (1.0 - phase) * 0.7F, 0.03F);
        }
    }

    private void drawMark(float height, int color, float alpha, float scale) {
        GL11.glPushMatrix();
        GL11.glTranslated(0.0, height + 0.4 + Math.sin(this.now * 2.0) * 0.06, 0.0);
        flake(0.11F * scale, color, alpha, 0.0F);
        GL11.glPopMatrix();
    }

    private static final int SEGMENTS = 64;

    private static void band(float radius, float half, int color, float alpha, float y) {
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        colour(color, alpha);
        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = i * Math.PI * 2.0 / SEGMENTS;
            double c = Math.cos(angle);
            double s = Math.sin(angle);
            GL11.glVertex3d(c * (radius - half), y, s * (radius - half));
            GL11.glVertex3d(c * (radius + half), y, s * (radius + half));
        }
        GL11.glEnd();
    }

    private static void glow(float radius, float half, int color, float alpha) {
        for (int side = -1; side <= 1; side += 2) {
            GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
            for (int i = 0; i <= SEGMENTS; i++) {
                double angle = i * Math.PI * 2.0 / SEGMENTS;
                double c = Math.cos(angle);
                double s = Math.sin(angle);
                colour(color, alpha);
                GL11.glVertex3d(c * radius, 0.0, s * radius);
                colour(color, 0.0F);
                GL11.glVertex3d(c * (radius + side * half), 0.0, s * (radius + side * half));
            }
            GL11.glEnd();
        }
    }

    private static void disc(double x, double y, double z, float radius, int color, float alpha) {
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        colour(color, alpha);
        GL11.glVertex3d(x, y, z);
        colour(color, alpha * 0.2F);
        for (int i = 0; i <= 12; i++) {
            double angle = i * Math.PI * 2.0 / 12;
            GL11.glVertex3d(x + Math.cos(angle) * radius, y, z + Math.sin(angle) * radius);
        }
        GL11.glEnd();
    }

    private static void colour(int color, float alpha) {
        GlStateManager.color(((color >> 16) & 0xFF) / 255.0F, ((color >> 8) & 0xFF) / 255.0F, (color & 0xFF) / 255.0F,
                Math.max(0.0F, Math.min(1.0F, alpha)));
    }

    @atlas.event.EventTarget(whenDisabled = true)
    public void onWorldDropEntities(atlas.events.LoadWorldEvent event) {
        this.shown = null;
        this.leaving = null;
        this.lastHit = null;
    }
}
