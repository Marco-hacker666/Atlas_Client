package atlas.script;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.AttackEvent;
import atlas.events.KeyEvent;
import atlas.events.LeftClickMouseEvent;
import atlas.events.LoadWorldEvent;
import atlas.events.MoveInputEvent;
import atlas.events.PacketEvent;
import atlas.events.PlayerUpdateEvent;
import atlas.events.Render2DEvent;
import atlas.events.Render3DEvent;
import atlas.events.TickEvent;
import atlas.events.UpdateEvent;
import atlas.management.RotationHelper;
import atlas.mixin.IAccessorC03PacketPlayer;
import atlas.mixin.IAccessorEntityRenderer;
import atlas.mixin.IAccessorMinecraft;
import atlas.script.raven.RavenHooks;
import atlas.script.raven.model.Entity;
import atlas.script.raven.model.MovementInput;
import atlas.script.raven.model.NetworkPlayer;
import atlas.script.raven.model.PlayerState;
import atlas.script.raven.model.Vec3;
import atlas.script.raven.packet.clientbound.SPacket;
import atlas.script.raven.packet.serverbound.CPacket;
import atlas.script.raven.packet.serverbound.PacketHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.common.gameevent.TickEvent.RenderTickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.lwjgl.opengl.GL11;

import java.util.List;

public final class ScriptDispatcher {
    public static final ScriptDispatcher INSTANCE = new ScriptDispatcher();
    private static final Minecraft mc = Minecraft.getMinecraft();

    private final Object ravenLook = new Object();

    private volatile PlayerState packetState;
    private volatile boolean packetPosition;
    private volatile boolean packetGround;

    private boolean restore;
    private float sentYaw;
    private float sentPitch;
    private float cameraYaw;
    private float cameraPitch;
    private boolean cameraMoved;
    private boolean restoreSprint;
    private boolean sprintWas;
    private boolean restoreSneak;
    private boolean sneakWas;
    private int ticks;

    private ScriptDispatcher() {
    }

    private interface Which {
        boolean takes(ScriptKind kind);
    }

    private static final Which ALL = kind -> true;
    private static final Which ATLAS = kind -> kind == ScriptKind.ATLAS;
    private static final Which BS17 = ScriptKind::bs17;
    private static final Which BS16 = kind -> kind == ScriptKind.RAVEN16;
    private static final Which BS16_ATLAS = kind -> kind != ScriptKind.RAVEN17;

    private static List<ScriptEntry> running() {
        return ScriptManager.get().running;
    }

    private static ScriptRuntime live(ScriptEntry entry) {
        ScriptHost host = entry.host;
        ScriptRuntime runtime = entry.runtime;
        return host != null && runtime != null && host.isEnabled() ? runtime : null;
    }

    private static boolean inWorld() {
        return mc.thePlayer != null && mc.theWorld != null;
    }

    private static boolean noScriptOn() {
        for (ScriptEntry entry : running()) {
            if (live(entry) != null) {
                return false;
            }
        }
        return true;
    }

    private static void call(Which which, String hook, Object... args) {
        for (ScriptEntry entry : running()) {
            ScriptRuntime runtime = live(entry);
            if (runtime != null && which.takes(entry.kind)) {
                runtime.call(hook, args);
            }
        }
    }

    private static boolean ask(Which which, String hook, Object... args) {
        boolean allowed = true;
        for (ScriptEntry entry : running()) {
            ScriptRuntime runtime = live(entry);
            if (runtime != null && which.takes(entry.kind) && runtime.ask(hook, args) == 0) {
                allowed = false;
            }
        }
        return allowed;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        ScriptManager.get().tick();
        if (++this.ticks % 600 == 0) {
            Entity.prune();
        }
        if (RavenHooks.edgeCrouchHeld && noScriptOn()) {
            RavenHooks.edgeCrouchHeld = false;
        }
        if (inWorld()) {
            call(ATLAS, "onTick");
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onUpdate(UpdateEvent event) {
        if (!inWorld()) {
            return;
        }
        if (event.getType() == EventType.POST) {
            this.undoPacketState();
            call(ALL, "onPostMotion");
            call(ALL, "onPostUpdate");
            call(ATLAS, "onPostUpdate", event);
            return;
        }
        call(ALL, "onPreUpdate");
        call(ATLAS, "onUpdate", event);
        for (ScriptEntry entry : running()) {
            ScriptRuntime runtime = live(entry);
            Float[] look = runtime == null ? null : runtime.value("getRotations", Float[].class);
            if (look == null || look.length == 0 || look.length > 2) {
                continue;
            }
            if (look[0] != null) {
                RavenHooks.wantedYaw = look[0];
            }
            if (look.length == 2 && look[1] != null) {
                RavenHooks.wantedPitch = look[1];
            }
        }
        Float yaw = RavenHooks.wantedYaw;
        Float pitch = RavenHooks.wantedPitch;
        RavenHooks.wantedYaw = null;
        RavenHooks.wantedPitch = null;
        if (yaw == null && pitch == null) {
            RavenHooks.appliedYaw = null;
            RavenHooks.appliedPitch = null;
            return;
        }

        float[] got = RotationHelper.request(this.ravenLook, yaw != null ? yaw : event.getNewYaw(),
                pitch != null ? pitch : event.getNewPitch(), ScriptManager.PRIORITY,
                RavenHooks.movementFix ? RotationHelper.MOVE_FIX : RotationHelper.MOVE_CAMERA, false,
                RotationHelper.NO_LIMIT, 360.0F);
        RavenHooks.appliedYaw = got == null ? null : got[0];
        RavenHooks.appliedPitch = got == null ? null : got[1];
    }

    @EventTarget(Priority.HIGHEST)
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!inWorld() || running().isEmpty()) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        float yawBefore = player.rotationYaw;
        float pitchBefore = player.rotationPitch;
        PlayerState state = null;
        PlayerState before = null;
        for (ScriptEntry entry : running()) {
            ScriptRuntime runtime = live(entry);
            if (runtime == null || !runtime.has("onPreMotion")) {
                continue;
            }
            if (state == null) {
                state = new PlayerState(player.posX, player.getEntityBoundingBox().minY, player.posZ,
                        player.rotationYaw, player.rotationPitch, player.onGround, player.isSprinting(),
                        player.isSneaking());
                before = state.copy();
            }
            runtime.call("onPreMotion", state);
        }

        Float wantedYaw = RavenHooks.wantedYaw;
        Float wantedPitch = RavenHooks.wantedPitch;
        if (wantedYaw != null || wantedPitch != null) {
            RavenHooks.wantedYaw = null;
            RavenHooks.wantedPitch = null;
            if (state == null) {
                state = new PlayerState(player.posX, player.getEntityBoundingBox().minY, player.posZ, yawBefore,
                        pitchBefore, player.onGround, player.isSprinting(), player.isSneaking());
                before = state.copy();
            }
            if (wantedYaw != null) {
                state.yaw = wantedYaw;
            }
            if (wantedPitch != null) {
                state.pitch = wantedPitch;
            }
            RavenHooks.appliedYaw = state.yaw;
            RavenHooks.appliedPitch = state.pitch;
        }
        if (state == null || state.equals(before)) {
            return;
        }
        if (state.yaw != before.yaw || state.pitch != before.pitch) {
            this.cameraYaw = player.rotationYaw;
            this.cameraPitch = player.rotationPitch;
            this.cameraMoved = player.rotationYaw != yawBefore || player.rotationPitch != pitchBefore;
            if (!this.cameraMoved) {
                this.cameraYaw = yawBefore;
                this.cameraPitch = pitchBefore;
            }
            player.rotationYaw = state.yaw;
            player.rotationPitch = state.pitch;
            this.sentYaw = state.yaw;
            this.sentPitch = state.pitch;
            this.restore = true;
        }
        this.packetPosition = state.x != before.x || state.y != before.y || state.z != before.z;
        this.packetGround = state.onGround != before.onGround;
        this.packetState = this.packetPosition || this.packetGround ? state : null;
        if (state.isSprinting != before.isSprinting) {
            this.sprintWas = player.isSprinting();
            this.restoreSprint = true;
            player.setSprinting(state.isSprinting);
        }
        if (state.isSneaking != before.isSneaking && player.movementInput != null) {
            this.sneakWas = player.movementInput.sneak;
            this.restoreSneak = true;
            player.movementInput.sneak = state.isSneaking;
        }
    }

    private void undoPacketState() {
        EntityPlayerSP player = mc.thePlayer;
        if (this.restore) {
            this.restore = false;

            if (this.cameraMoved || player.rotationYaw == this.sentYaw && player.rotationPitch == this.sentPitch) {
                player.rotationYaw = this.cameraYaw;
                player.rotationPitch = this.cameraPitch;
            }
        }
        if (this.restoreSprint) {
            this.restoreSprint = false;
            player.setSprinting(this.sprintWas);
        }
        if (this.restoreSneak) {
            this.restoreSneak = false;
            if (player.movementInput != null) {
                player.movementInput.sneak = this.sneakWas;
            }
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onMoveInput(MoveInputEvent event) {
        if (!inWorld() || mc.thePlayer.movementInput == null || running().isEmpty()) {
            return;
        }
        net.minecraft.util.MovementInput keys = mc.thePlayer.movementInput;
        MovementInput input = null;
        MovementInput before = null;
        for (ScriptEntry entry : running()) {
            ScriptRuntime runtime = live(entry);
            if (runtime == null || !runtime.has("onPrePlayerInput")) {
                continue;
            }
            if (input == null) {
                float slow = keys.sneak ? 0.3F : 1.0F;
                input = new MovementInput(keys.moveForward / slow, keys.moveStrafe / slow, keys.jump, keys.sneak);
                before = new MovementInput(input.asArray());
            }
            runtime.call("onPrePlayerInput", input);
        }
        if (input != null && !input.equals(before)) {
            float slow = input.sneak ? 0.3F : 1.0F;
            keys.moveForward = input.forward * slow;
            keys.moveStrafe = input.strafe * slow;
            keys.jump = input.jump;
            keys.sneak = input.sneak;
        }
        call(ALL, "onPostPlayerInput");
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        Packet<?> packet = event.getPacket();
        if (packet == null || event.isCancelled()) {
            return;
        }
        if (event.getType() == EventType.SEND) {
            PlayerState state = this.packetState;
            if (state != null && packet instanceof C03PacketPlayer) {
                this.packetState = null;
                IAccessorC03PacketPlayer access = (IAccessorC03PacketPlayer) packet;
                if (this.packetPosition && ((C03PacketPlayer) packet).isMoving()) {
                    access.setX(state.x);
                    access.setY(state.y);
                    access.setZ(state.z);
                }
                if (this.packetGround) {
                    access.setOnGround(state.onGround);
                }
            }
            if (running().isEmpty()) {
                return;
            }
            CPacket wrapped = null;
            boolean allowed = true;
            for (ScriptEntry entry : running()) {
                ScriptRuntime runtime = live(entry);
                if (runtime == null || !runtime.has("onPacketSent")) {
                    continue;
                }
                if (wrapped == null) {
                    wrapped = PacketHandler.convertServerBound(packet);
                    if (wrapped == null) {
                        return;
                    }
                }
                if (runtime.ask("onPacketSent", wrapped) == 0) {
                    allowed = false;
                }
            }
            if (!allowed) {
                event.setCancelled(true);
            }
        } else if (event.getType() == EventType.RECEIVE) {
            if (running().isEmpty()) {
                return;
            }
            SPacket wrapped = null;
            boolean allowed = true;
            for (ScriptEntry entry : running()) {
                ScriptRuntime runtime = live(entry);
                if (runtime == null || !runtime.has("onPacketReceived")) {
                    continue;
                }
                if (wrapped == null) {
                    wrapped = PacketHandler.convertClientBound(packet);
                    if (wrapped == null) {
                        return;
                    }
                }
                if (runtime.ask("onPacketReceived", wrapped) == 0) {
                    allowed = false;
                }
            }
            if (!allowed) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onLeftClick(LeftClickMouseEvent event) {
        if (inWorld() && !event.isCancelled() && !running().isEmpty() && !ask(ALL, "onPreAttack")) {
            event.setCancelled(true);
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onAttack(AttackEvent event) {
        if (!inWorld() || event.isCancelled() || running().isEmpty()) {
            return;
        }
        Entity target = Entity.convert(event.getTarget());
        boolean allowed = ask(ALL, "onAttackEntity", target, Entity.convert(mc.thePlayer));
        allowed &= ask(ATLAS, "onAttack", target);
        if (!allowed) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onKey(KeyEvent event) {
        if (inWorld() && !running().isEmpty()) {
            call(ATLAS, "onKey", event.getKey());
        }
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        Entity.clearCache();
        NetworkPlayer.clearCache();
        RavenHooks.reset();
        this.packetState = null;
        this.restore = false;
        this.restoreSprint = false;
        this.restoreSneak = false;
        call(ATLAS, "onWorldLoad");
    }

    public void onPlayerMove(double x, double y, double z) {
        if (inWorld() && !running().isEmpty()) {
            call(ALL, "onPlayerMove", new Vec3(x, y, z));
        }
    }

    public void onAntiCheatFlag(String flag, net.minecraft.entity.Entity entity) {
        if (!mc.isCallingFromMinecraftThread()) {
            mc.addScheduledTask(() -> this.onAntiCheatFlag(flag, entity));
            return;
        }
        if (inWorld() && !running().isEmpty()) {
            call(ALL, "onAntiCheatFlag", flag, Entity.convert(entity));
        }
    }

    public void onDispatch(Packet<?> packet) {
        if (!inWorld() || running().isEmpty()) {
            return;
        }
        CPacket wrapped = null;
        for (ScriptEntry entry : running()) {
            ScriptRuntime runtime = live(entry);
            if (runtime == null || !runtime.has("onDispatchPacket")) {
                continue;
            }
            if (wrapped == null) {
                wrapped = PacketHandler.convertServerBound(packet);
                if (wrapped == null) {
                    return;
                }
            }
            runtime.call("onDispatchPacket", wrapped);
        }
    }

    public void onKeyState(int key, boolean down, boolean inGui) {
        if (!inWorld() || running().isEmpty()) {
            return;
        }
        call(BS17, "onKey", org.lwjgl.input.Keyboard.getKeyName(key), key, down, inGui);
    }

    public boolean onScreenKey(char typed, int key) {
        if (!inWorld() || running().isEmpty()) {
            return false;
        }
        return !ask(ALL, "onKeyPress", typed, key);
    }

    public void onPreInteract() {
        if (inWorld() && !running().isEmpty()) {
            call(ALL, "onPrePlayerInteract");
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (inWorld() && !running().isEmpty()) {
            call(ATLAS, "onRender2D", event.getPartialTicks());
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (inWorld() && !running().isEmpty()) {
            call(ATLAS, "onRender3D", event.getPartialTicks());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderTick(RenderTickEvent event) {
        if (event.phase != Phase.END || !inWorld() || running().isEmpty()) {
            return;
        }
        for (ScriptEntry entry : running()) {
            ScriptRuntime runtime = live(entry);
            if (runtime == null || !runtime.has("onRenderTick")) {
                continue;
            }
            if (entry.kind.bs17()) {
                mc.entityRenderer.setupOverlayRendering();
                runtime.call("onRenderTick", event.renderTickTime);
                restoreOverlayState();
            } else {
                runtime.call("onRenderTick", event.renderTickTime);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!inWorld() || running().isEmpty()) {
            return;
        }
        float partial = ((IAccessorMinecraft) mc).getTimer().renderPartialTicks;
        ((IAccessorEntityRenderer) mc.entityRenderer).callSetupCameraTransform(partial, 0);
        RavenHooks.captureMatrices();
        for (ScriptEntry entry : running()) {
            ScriptRuntime runtime = live(entry);
            if (runtime == null || !runtime.has("onRenderWorld")) {
                continue;
            }
            ((IAccessorEntityRenderer) mc.entityRenderer).callSetupCameraTransform(partial, 0);
            runtime.call("onRenderWorld", event.partialTicks);
            if (entry.kind.bs17()) {
                restoreWorldState(partial);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPreRenderModel(RenderLivingEvent.Pre event) {
        if (inWorld() && !running().isEmpty() && !ask(BS17, "onPreRenderModel", Entity.convert(event.entity))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPostRenderModel(RenderLivingEvent.Post event) {
        if (inWorld() && !running().isEmpty()) {
            call(BS17, "onPostRenderModel", Entity.convert(event.entity));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onScreenRender(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!running().isEmpty()) {
            call(BS17, "onScreenRender", event.gui == null ? "" : event.gui.getClass().getSimpleName(), event.mouseX,
                    event.mouseY, event.renderPartialTicks);
        }
    }

    private static void restoreOverlayState() {
        mc.entityRenderer.setupOverlayRendering();
        restoreCommonState();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.disableLighting();
        GlStateManager.disableBlend();
        RenderHelper.disableStandardItemLighting();
    }

    private static void restoreWorldState(float partial) {
        ((IAccessorEntityRenderer) mc.entityRenderer).callSetupCameraTransform(partial, 0);
        restoreCommonState();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.disableFog();
        RenderHelper.disableStandardItemLighting();
    }

    private static void restoreCommonState() {
        GlStateManager.resetColor();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glLineWidth(1.0F);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent event) {
        if (!inWorld() || running().isEmpty()) {
            return;
        }
        boolean allowed = ask(BS16, "onMouse", event.button, event.buttonstate);
        if (event.button != -1 || event.dwheel != 0) {
            boolean state = event.button == -1 ? event.dwheel > 0 : event.buttonstate;
            allowed &= ask(BS17, "onMouse", event.button, state, event.x, event.y);
        }
        if (!allowed) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.message == null || !inWorld() || running().isEmpty()) {
            return;
        }
        String text = event.message.getUnformattedText();
        boolean visible = false;
        for (char c : net.minecraft.util.StringUtils.stripControlCodes(text).toCharArray()) {
            if (c > '\u0014' && c < '\u007f') {
                visible = true;
                break;
            }
        }
        if (!visible) {
            return;
        }
        boolean allowed = event.type == 2 || ask(BS16_ATLAS, "onChat", text);
        allowed &= ask(BS17, "onChat", text, event.type);
        if (!allowed) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onJoin(EntityJoinWorldEvent event) {
        if (event.entity == null || event.world == null || !event.world.isRemote || running().isEmpty()) {
            return;
        }
        call(ALL, "onWorldJoin", Entity.convert(event.entity));
    }

    public void onGuiChange(GuiScreen next, GuiScreen current) {
        GuiScreen screen = next != null ? next : current;
        if (screen != null && !running().isEmpty()) {
            call(ALL, "onGuiUpdate", screen.getClass().getSimpleName(), next != null);
        }
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        mc.addScheduledTask(() -> call(ALL, "onDisconnect"));
    }
}
