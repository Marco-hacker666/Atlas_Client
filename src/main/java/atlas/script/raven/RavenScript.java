package atlas.script.raven;

import atlas.Atlas;
import atlas.event.EventManager;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import atlas.mixin.IAccessorMinecraft;
import atlas.mixin.IAccessorTimer;
import atlas.module.Category;
import atlas.module.Module;
import atlas.module.ModuleCategories;
import atlas.property.Property;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.KeyProperty;
import atlas.property.properties.ModeProperty;
import atlas.script.ScriptEntry;
import atlas.script.ScriptManager;
import atlas.script.ScriptSettings;
import atlas.script.raven.model.Block;
import atlas.script.raven.model.Bridge;
import atlas.script.raven.model.Entity;
import atlas.script.raven.model.Image;
import atlas.script.raven.model.ItemStack;
import atlas.script.raven.model.Message;
import atlas.script.raven.model.NetworkPlayer;
import atlas.script.raven.model.PlayerState;
import atlas.script.raven.model.TileEntity;
import atlas.script.raven.model.Vec3;
import atlas.script.raven.packet.clientbound.SPacket;
import atlas.script.raven.packet.serverbound.CPacket;
import atlas.util.ChatUtil;
import atlas.util.PacketUtil;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.client.C16PacketClientStatus;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Raven bS's script API under its names -- client, world, modules, gl, config, render (blur, bloom), inventory, keybinds, util and bridge -- written for Atlas ...

public class RavenScript {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public static final Bridge bridge = new Bridge();
    private static final ExecutorService ASYNC = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "Atlas-ScriptAsync");
        thread.setDaemon(true);
        return thread;
    });
    private static final Random RANDOM = new Random();

    private static final Map<String, Field> FIELDS = new java.util.concurrent.ConcurrentHashMap<String, Field>();

    public static void reloadModules() {
    }

    static Object field(Object owner, Class<?> type, String srg, String mcp) {
        try {
            String key = type.getName() + "#" + mcp;
            Field field = FIELDS.get(key);
            if (field == null) {
                try {
                    field = type.getDeclaredField(srg);
                } catch (NoSuchFieldException e) {
                    field = type.getDeclaredField(mcp);
                }
                field.setAccessible(true);
                FIELDS.put(key, field);
            }
            return field.get(owner);
        } catch (Exception e) {
            return null;
        }
    }

    static void setField(Object owner, Class<?> type, String srg, String mcp, Object value) {
        try {
            field(owner, type, srg, mcp);
            Field field = FIELDS.get(type.getName() + "#" + mcp);
            if (field != null) {
                field.set(owner, value);
            }
        } catch (Exception ignored) {
        }
    }

    static EnumFacing facing(String side) {
        try {
            return EnumFacing.valueOf(side.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return EnumFacing.UP;
        }
    }

    static float[] rotationsTo(double x, double y, double z) {
        double dx = x - mc.thePlayer.posX;
        double dy = y - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double dz = z - mc.thePlayer.posZ;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
        return new float[]{mc.thePlayer.rotationYaw + MathHelper.wrapAngleTo180_float(yaw - mc.thePlayer.rotationYaw),
                MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
    }

    public static class client {
        public static boolean allowFlying() {
            return mc.thePlayer.capabilities.allowFlying;
        }

        public static void removePotionEffect(int id) {
            mc.thePlayer.removePotionEffectClient(id);
        }

        public static int getUID() {
            return Math.abs(mc.getSession().getPlayerID().hashCode());
        }

        public static String getUser() {
            return mc.getSession().getUsername();
        }

        public static void addEnemy(String username) {
            Atlas.targetManager.add(username);
        }

        public static void addFriend(String username) {
            Atlas.friendManager.add(username);
        }

        public static void removeEnemy(String username) {
            Atlas.targetManager.remove(username);
        }

        public static void removeFriend(String username) {
            Atlas.friendManager.remove(username);
        }

        public static boolean isFriend(String username) {
            return Atlas.friendManager.isFriend(username);
        }

        public static boolean isEnemy(String username) {
            return Atlas.targetManager.isFriend(username);
        }

        public static void async(final Runnable method) {
            ASYNC.submit(() -> {
                try {
                    method.run();
                } catch (Throwable e) {
                    ChatUtil.sendFormatted("&7[&bScripts&7] &cError in client.async: " + e + "&r");
                }
            });
        }

        public static int getFPS() {
            return Minecraft.getDebugFPS();
        }

        public static void chat(String message) {
            mc.thePlayer.sendChatMessage(message);
        }

        public static void print(String string) {
            ChatUtil.sendRaw(util.color(string == null ? "null" : string));
        }

        public static void print(Message component) {
            ChatUtil.send(component.component);
        }

        public static void print(Object object) {
            print(String.valueOf(object));
        }

        public static boolean isDiagonal() {
            float yaw = ((MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw) % 90.0F) + 90.0F) % 90.0F;
            return Math.abs(yaw - 45.0F) < 15.0F;
        }

        public static void setTimer(float timer) {
            ((IAccessorTimer) ((IAccessorMinecraft) mc).getTimer()).setTimerSpeed(timer);
        }

        public static boolean isCreative() {
            return mc.thePlayer.capabilities.isCreativeMode;
        }

        public static void processPacket(SPacket packet) {
            processPacketNoEvent(packet);
        }

        @SuppressWarnings("unchecked")
        public static void processPacketNoEvent(SPacket packet) {
            Packet<?> raw = packet == null ? null : packet.packet != null ? packet.packet : packet.convert();
            if (raw != null) {
                PacketUtil.handlePacket((Packet<INetHandlerPlayClient>) raw);
            }
        }

        public static void multiplyMotion(double factor) {
            mc.thePlayer.motionX *= factor;
            mc.thePlayer.motionZ *= factor;
        }

        public static String getTitle() {
            Object title = field(mc.ingameGUI, net.minecraft.client.gui.GuiIngame.class, "field_175201_x", "displayedTitle");
            return title == null ? "" : String.valueOf(title);
        }

        public static String getSubTitle() {
            Object title = field(mc.ingameGUI, net.minecraft.client.gui.GuiIngame.class, "field_175200_y", "displayedSubTitle");
            return title == null ? "" : String.valueOf(title);
        }

        public static String getRecordPlaying() {
            Object record = field(mc.ingameGUI, net.minecraft.client.gui.GuiIngame.class, "field_73838_g", "recordPlaying");
            return record == null ? "" : String.valueOf(record);
        }

        public static boolean isFlying() {
            return mc.thePlayer.capabilities.isFlying;
        }

        public static void attack(Entity entity) {
            if (entity == null || entity.entity == null) {
                return;
            }
            mc.thePlayer.swingItem();
            mc.playerController.attackEntity(mc.thePlayer, entity.entity);
        }

        public static boolean isSinglePlayer() {
            return mc.isSingleplayer();
        }

        public static boolean isSpectator() {
            return mc.thePlayer.isSpectator();
        }

        public static void setFlying(boolean flying) {
            mc.thePlayer.capabilities.isFlying = flying;
        }

        public static void setJump(boolean jump) {
            mc.thePlayer.movementInput.jump = jump;
        }

        public static void setJumping(boolean jump) {
            mc.thePlayer.setJumping(jump);
        }

        public static void setRenderArmPitch(float pitch) {
            mc.thePlayer.renderArmPitch = pitch;
        }

        public static float getEquippedProgress() {
            Object progress = field(mc.getItemRenderer(), net.minecraft.client.renderer.ItemRenderer.class,
                    "field_78454_c", "equippedProgress");
            return progress instanceof Float ? (Float) progress : 0.0F;
        }

        public static void disconnect() {
            mc.addScheduledTask(() -> {
                boolean single = mc.isIntegratedServerRunning();
                if (mc.theWorld != null) {
                    mc.theWorld.sendQuittingDisconnectingPacket();
                }
                mc.loadWorld(null);
                mc.displayGuiScreen(single ? new GuiMainMenu() : new GuiMultiplayer(new GuiMainMenu()));
            });
        }

        public static float getRenderArmPitch() {
            return mc.thePlayer.renderArmPitch;
        }

        public static void setRenderArmYaw(float yaw) {
            mc.thePlayer.renderArmYaw = yaw;
        }

        public static float getRenderArmYaw() {
            return mc.thePlayer.renderArmYaw;
        }

        public static long getTotalMemory() {
            return Runtime.getRuntime().totalMemory();
        }

        public static long getFreeMemory() {
            return Runtime.getRuntime().freeMemory();
        }

        public static long getMaxMemory() {
            return Runtime.getRuntime().maxMemory();
        }

        public static List<String[]> getResourcePacks() {
            List<String[]> out = new ArrayList<String[]>();
            for (ResourcePackRepository.Entry entry : mc.getResourcePackRepository().getRepositoryEntries()) {
                out.add(new String[]{entry.getResourcePackName(), entry.getTexturePackDescription()});
            }
            return out;
        }

        public static void jump() {
            mc.thePlayer.jump();
        }

        public static boolean allowEditing() {
            return mc.thePlayer.capabilities.allowEdit;
        }

        public static void setItemInUseCount(int count) {
            setField(mc.thePlayer, EntityPlayer.class, "field_71072_f", "itemInUseCount", count);
        }

        public static int getItemInUseCount() {
            return mc.thePlayer.getItemInUseCount();
        }

        public static int getItemInUseDuration() {
            return mc.thePlayer.getItemInUseDuration();
        }

        public static void log(final Object obj) {
            System.out.println("[Atlas script] " + obj);
        }

        public static void setSneaking(boolean sneak) {
            mc.thePlayer.setSneaking(sneak);
        }

        public static void setSneak(boolean sneak) {
            mc.thePlayer.movementInput.sneak = sneak;
        }

        public static boolean isSneak() {
            return mc.thePlayer.movementInput.sneak;
        }

        public static Entity getPlayer() {
            return Entity.convert(mc.thePlayer);
        }

        public static boolean isRiding() {
            return mc.thePlayer.isRiding();
        }

        public static Vec3 getMotion() {
            return new Vec3(mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ);
        }

        public static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public static void sleep(int ms) {
            sleep((long) ms);
        }

        public static void ping() {
            mc.thePlayer.playSound("note.pling", 1.0F, 1.0F);
        }

        public static void playSound(String name, float volume, float pitch) {
            mc.thePlayer.playSound(name, volume, pitch);
        }

        public static boolean isMoving() {
            return atlas.util.MoveUtil.isMoving();
        }

        public static boolean isJump() {
            return mc.thePlayer.movementInput.jump;
        }

        public static float getStrafe() {
            return mc.thePlayer.movementInput.moveStrafe;
        }

        public static float getForward() {
            return mc.thePlayer.movementInput.moveForward;
        }

        public static void closeScreen() {
            if (mc.currentScreen != null) {
                mc.thePlayer.closeScreen();
            }
        }

        public static String getScreen() {
            return mc.currentScreen == null ? "" : mc.currentScreen.getClass().getSimpleName();
        }

        public static float[] getRotationsToEntity(Entity entity) {
            net.minecraft.entity.Entity target = entity.entity;
            AxisAlignedBB box = target.getEntityBoundingBox();
            return rotationsTo(target.posX, (box.minY + box.maxY) / 2.0, target.posZ);
        }

        public static void sendPacket(CPacket packet) {
            Packet<?> raw = packet == null ? null : packet.convert();
            if (raw != null) {
                PacketUtil.sendPacket(raw);
            }
        }

        public static void sendPacketNoEvent(CPacket packet) {
            Packet<?> raw = packet == null ? null : packet.convert();
            if (raw != null) {
                PacketUtil.sendPacketNoEvent(raw);
            }
        }

        public static boolean inFocus() {
            return mc.inGameHasFocus;
        }

        public static void dropItem(boolean dropStack) {
            mc.thePlayer.dropOneItem(dropStack);
        }

        public static void setMotion(double x, double y, double z) {
            mc.thePlayer.motionX = x;
            mc.thePlayer.motionY = y;
            mc.thePlayer.motionZ = z;
        }

        public static void setSpeed(double speed) {
            atlas.util.MoveUtil.setSpeed(speed);
        }

        public static void setForward(float forward) {
            mc.thePlayer.movementInput.moveForward = forward;
        }

        public static void setStrafe(float strafe) {
            mc.thePlayer.movementInput.moveStrafe = strafe;
        }

        public static String getServerIP() {
            if (mc.getCurrentServerData() == null || mc.isSingleplayer()) {
                return "";
            }
            return mc.getCurrentServerData().serverIP;
        }

        public static int[] getDisplaySize() {
            ScaledResolution resolution = new ScaledResolution(mc);
            return new int[]{resolution.getScaledWidth(), resolution.getScaledHeight(), resolution.getScaleFactor()};
        }

        public static float getServerDirection(PlayerState state) {
            return state.yaw;
        }

        public static Object[] raycastBlock(final double distance) {
            return raycastBlock(distance, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
        }

        public static Object[] raycastBlock(final double distance, final float yaw, final float pitch) {
            net.minecraft.util.Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
            net.minecraft.util.Vec3 look = look(yaw, pitch);
            net.minecraft.util.Vec3 end = eyes.addVector(look.xCoord * distance, look.yCoord * distance, look.zCoord * distance);
            MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(eyes, end, false, false, false);
            if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                return null;
            }
            Vec3 block = new Vec3(hit.getBlockPos());
            Vec3 offset = new Vec3(hit.hitVec.xCoord - block.x, hit.hitVec.yCoord - block.y, hit.hitVec.zCoord - block.z);
            return new Object[]{block, offset, hit.sideHit.name()};
        }

        public static Object[] raycastEntity(final double distance) {
            return raycastEntity(distance, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
        }

        public static Object[] raycastEntity(final double distance, final float yaw, final float pitch) {
            net.minecraft.util.Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
            net.minecraft.util.Vec3 look = look(yaw, pitch);
            net.minecraft.util.Vec3 end = eyes.addVector(look.xCoord * distance, look.yCoord * distance, look.zCoord * distance);
            MovingObjectPosition wall = mc.theWorld.rayTraceBlocks(eyes, end, false, false, false);
            double reach = wall == null ? distance : wall.hitVec.distanceTo(eyes);
            net.minecraft.entity.Entity best = null;
            net.minecraft.util.Vec3 bestHit = null;
            double bestDistance = reach;
            for (net.minecraft.entity.Entity entity : mc.theWorld.getEntitiesWithinAABBExcludingEntity(mc.thePlayer,
                    mc.thePlayer.getEntityBoundingBox().addCoord(look.xCoord * distance, look.yCoord * distance,
                            look.zCoord * distance).expand(1.0, 1.0, 1.0))) {
                if (!(entity instanceof EntityLivingBase) || !entity.canBeCollidedWith()
                        || ((EntityLivingBase) entity).deathTime != 0) {
                    continue;
                }
                float border = entity.getCollisionBorderSize();
                AxisAlignedBB box = entity.getEntityBoundingBox().expand(border, border, border);
                if (box.isVecInside(eyes)) {
                    best = entity;
                    bestHit = eyes;
                    bestDistance = 0.0;
                    continue;
                }
                MovingObjectPosition through = box.calculateIntercept(eyes, end);
                if (through != null) {
                    double d = eyes.distanceTo(through.hitVec);
                    if (d < bestDistance) {
                        best = entity;
                        bestHit = through.hitVec;
                        bestDistance = d;
                    }
                }
            }
            if (best == null) {
                return null;
            }
            Vec3 offset = new Vec3(bestHit.xCoord - best.posX, bestHit.yCoord - best.posY, bestHit.zCoord - best.posZ);
            return new Object[]{Entity.convert(best), offset, eyes.squareDistanceTo(bestHit)};
        }

        static net.minecraft.util.Vec3 look(float yaw, float pitch) {
            float cosYaw = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
            float sinYaw = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
            float cosPitch = -MathHelper.cos(-pitch * 0.017453292F);
            float sinPitch = MathHelper.sin(-pitch * 0.017453292F);
            return new net.minecraft.util.Vec3(sinYaw * cosPitch, sinPitch, cosYaw * cosPitch);
        }

        public static boolean canPlaceBlock(ItemStack stack, Vec3 pos, String side) {
            if (stack == null || stack.itemStack == null || !(stack.itemStack.getItem() instanceof ItemBlock)) {
                return false;
            }
            return ((ItemBlock) stack.itemStack.getItem()).canPlaceBlockOnSide(mc.theWorld, Vec3.getBlockPos(pos),
                    facing(side), mc.thePlayer, stack.itemStack);
        }

        public static boolean placeBlock(Vec3 targetPos, String side, Vec3 hitVec) {
            return mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem(),
                    Vec3.getBlockPos(targetPos), facing(side), Vec3.getVec3(hitVec));
        }

        public static void enableMovementFix() {
            RavenHooks.movementFix = true;
        }

        public static void disableMovementFix() {
            RavenHooks.movementFix = false;
        }

        public static boolean isMovementFixActive() {
            return RavenHooks.movementFix && RavenHooks.appliedYaw != null;
        }

        public static boolean isRotationActive() {
            return RavenHooks.appliedYaw != null || RavenHooks.appliedPitch != null;
        }

        public static void setRotations(float yaw, float pitch) {
            RavenHooks.wantedYaw = yaw;
            RavenHooks.wantedPitch = pitch;
        }

        public static void setYaw(float yaw) {
            RavenHooks.wantedYaw = yaw;
        }

        public static void setPitch(float pitch) {
            RavenHooks.wantedPitch = pitch;
        }

        public static Float getServerYaw() {
            return RavenHooks.appliedYaw;
        }

        public static Float getServerPitch() {
            return RavenHooks.appliedPitch;
        }

        public static float[] getRotationsToBlock(Vec3 position) {
            return rotationsTo(Math.floor(position.x) + 0.5, Math.floor(position.y) + 0.5, Math.floor(position.z) + 0.5);
        }

        public static void setSprinting(boolean sprinting) {
            mc.thePlayer.setSprinting(sprinting);
        }

        public static void swing() {
            mc.thePlayer.swingItem();
        }

        public static long time() {
            return System.currentTimeMillis();
        }
    }

    public static class world {
        public static Block getBlockAt(int x, int y, int z) {
            return new Block(x, y, z);
        }

        public static Block getBlockAt(Vec3 pos) {
            return new Block(pos);
        }

        public static String getDimension() {
            return mc.theWorld == null ? "" : mc.theWorld.provider.getDimensionName();
        }

        public static List<Entity> getEntities() {
            List<Entity> out = new ArrayList<Entity>();
            for (net.minecraft.entity.Entity entity : new ArrayList<net.minecraft.entity.Entity>(mc.theWorld.loadedEntityList)) {
                out.add(Entity.convert(entity));
            }
            return out;
        }

        public static Entity getEntityById(int entityId) {
            return Entity.convert(mc.theWorld.getEntityByID(entityId));
        }

        public static List<NetworkPlayer> getNetworkPlayers() {
            List<NetworkPlayer> out = new ArrayList<NetworkPlayer>();
            if (mc.getNetHandler() == null) {
                return out;
            }
            for (NetworkPlayerInfo info : new ArrayList<NetworkPlayerInfo>(mc.getNetHandler().getPlayerInfoMap())) {
                out.add(NetworkPlayer.convert(info));
            }
            return out;
        }

        public static List<Entity> getPlayerEntities() {
            List<Entity> out = new ArrayList<Entity>();
            for (EntityPlayer player : new ArrayList<EntityPlayer>(mc.theWorld.playerEntities)) {
                out.add(Entity.convert(player));
            }
            return out;
        }

        public static List<String> getScoreboard() {
            if (mc.theWorld == null) {
                return null;
            }
            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
            if (objective == null) {
                return null;
            }
            List<String> lines = new ArrayList<String>();
            lines.add(objective.getDisplayName());
            List<Score> scores = new ArrayList<Score>();
            for (Score score : scoreboard.getSortedScores(objective)) {
                if (score.getPlayerName() != null && !score.getPlayerName().startsWith("#")) {
                    scores.add(score);
                }
            }
            if (scores.size() > 15) {
                scores = scores.subList(scores.size() - 15, scores.size());
            }
            for (int i = scores.size() - 1; i >= 0; i--) {
                String name = scores.get(i).getPlayerName();
                lines.add(ScorePlayerTeam.formatPlayerName(scoreboard.getPlayersTeam(name), name));
            }
            return lines;
        }

        public static String getTabHeader() {
            return tabText("field_175256_i", "header");
        }

        public static String getTabFooter() {
            return tabText("field_175255_h", "footer");
        }

        private static String tabText(String srg, String mcp) {
            Object text = field(mc.ingameGUI.getTabList(), GuiPlayerTabOverlay.class, srg, mcp);
            return text instanceof IChatComponent ? ((IChatComponent) text).getFormattedText() : "";
        }

        public static Map<String, List<String>> getTeams() {
            Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
            if (mc.theWorld == null) {
                return out;
            }
            for (ScorePlayerTeam team : mc.theWorld.getScoreboard().getTeams()) {
                out.put(team.getRegisteredName(), new ArrayList<String>(team.getMembershipCollection()));
            }
            return out;
        }

        public static List<TileEntity> getTileEntities() {
            List<TileEntity> out = new ArrayList<TileEntity>();
            for (net.minecraft.tileentity.TileEntity tile : new ArrayList<net.minecraft.tileentity.TileEntity>(
                    mc.theWorld.loadedTileEntityList)) {
                out.add(new TileEntity(tile));
            }
            return out;
        }
    }

    public static class modules {
        private static final Map<String, String> ALIASES = new HashMap<String, String>();

        static {
            ALIASES.put("bedaura", "BedNuker");
            ALIASES.put("antiknockback", "Velocity");
            ALIASES.put("stealer", "ChestStealer");
            ALIASES.put("invmanager", "InvManager");
            ALIASES.put("killaura", "KillAura");
            ALIASES.put("autoblock", "BlockHit");
        }

        private final String scriptName;

        public modules(String scriptName) {
            this.scriptName = scriptName;
        }

        private ScriptEntry self() {
            return ScriptSettings.entry(this.scriptName);
        }

        static Module find(String moduleName) {
            if (moduleName == null || Atlas.moduleManager == null) {
                return null;
            }
            ScriptEntry entry = ScriptManager.get().byName(moduleName);
            if (entry != null) {
                return entry.module();
            }
            Module module = Atlas.moduleManager.getModule(moduleName);
            if (module == null) {
                String alias = ALIASES.get(moduleName.replace(" ", "").toLowerCase(Locale.ROOT));
                module = alias == null ? null : Atlas.moduleManager.getModule(alias);
            }
            return module;
        }

        private static boolean isSafeWalk(String moduleName) {
            return "safewalk".equals(moduleName.replace(" ", "").toLowerCase(Locale.ROOT));
        }

        public void enable(String moduleName) {
            Module module = find(moduleName);
            if (module == null) {
                return;
            }
            if (isSafeWalk(moduleName) && RavenHooks.edgeCrouchHeld) {
                RavenHooks.edgeCrouchHeld = false;
                if (!RavenHooks.safeWalkWasOn) {
                    return;
                }
            }
            module.setEnabled(true);
        }

        public void disable(String moduleName) {
            Module module = find(moduleName);
            if (module == null) {
                return;
            }
            if (isSafeWalk(moduleName) && !RavenHooks.edgeCrouchHeld) {
                RavenHooks.safeWalkWasOn = module.isEnabled();
                RavenHooks.edgeCrouchHeld = true;
            }
            module.setEnabled(false);
        }

        public boolean isEnabled(String moduleName) {
            Module module = find(moduleName);
            if (module == null) {
                return false;
            }
            if (isSafeWalk(moduleName) && !RavenHooks.edgeCrouchHeld
                    && Atlas.moduleManager.modules.get(atlas.module.modules.Eagle.class).isEnabled()) {
                return true;
            }
            return module.isEnabled();
        }

        public boolean isHidden(String moduleName) {
            Module module = find(moduleName);
            return module != null && module.isHidden();
        }

        public Entity getKillAuraTarget() {
            Module aura = Atlas.moduleManager.getModule(atlas.module.modules.KillAura.class);
            if (aura == null || !aura.isEnabled()) {
                return null;
            }
            return Entity.convert(((atlas.module.modules.KillAura) aura).getTarget());
        }

        public Map<String, Object> getSettings(String name) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            Module module = find(name);
            if (module == null) {
                return out;
            }
            ScriptEntry entry = ScriptManager.of(module);
            List<Property<?>> properties = entry != null ? new ArrayList<Property<?>>(entryProperties(entry))
                    : Atlas.propertyManager.properties.get(module.getClass());
            if (properties != null) {
                for (Property<?> property : properties) {
                    out.put(property.getLabel(), property.getValue());
                }
            }
            return out;
        }

        private static List<Property<?>> entryProperties(ScriptEntry entry) {
            List<Property<?>> list = Atlas.propertyManager.properties.get(entry.module().getClass());
            return list == null ? new ArrayList<Property<?>>() : list;
        }

        public Map<String, List<String>> getCategories() {
            Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
            for (Category category : Category.values()) {
                List<String> names = new ArrayList<String>();
                for (Module module : ModuleCategories.modulesIn(category, Atlas.moduleManager.modules)) {
                    names.add(module.getName());
                }
                if (!names.isEmpty()) {
                    out.put(category.displayName(), names);
                }
            }
            List<String> scripts = new ArrayList<String>();
            for (ScriptEntry entry : ScriptManager.get().entries()) {
                scripts.add(entry.name);
            }
            out.put("Scripts", scripts);
            return out;
        }

        public Vec3 getBedAuraPosition() {
            atlas.module.modules.BedNuker nuker = bedNuker();
            return nuker == null ? null : Vec3.convert(nuker.getTargetBed());
        }

        public float[] getBedAuraProgress() {
            atlas.module.modules.BedNuker nuker = bedNuker();
            return new float[]{nuker == null ? 0.0F : nuker.getBreakProgress(), 0.0F};
        }

        private static atlas.module.modules.BedNuker bedNuker() {
            Module module = Atlas.moduleManager.getModule(atlas.module.modules.BedNuker.class);
            return module != null && module.isEnabled() ? (atlas.module.modules.BedNuker) module : null;
        }

        public boolean isScaffolding() {
            Module scaffold = Atlas.moduleManager.getModule(atlas.module.modules.Scaffold.class);
            return scaffold != null && scaffold.isEnabled();
        }

        public boolean isTowering() {
            Module module = Atlas.moduleManager.getModule(atlas.module.modules.Scaffold.class);
            if (module == null || !module.isEnabled()) {
                return false;
            }
            atlas.module.modules.Scaffold scaffold = (atlas.module.modules.Scaffold) module;
            return scaffold.tower.getValue() != 0 && mc.gameSettings.keyBindJump.isKeyDown();
        }

        public void registerGroup(String name) {
            ScriptSettings.group(this.self(), name);
        }

        public void registerDescription(String description) {
            ScriptSettings.group(this.self(), description);
        }

        public void registerButton(String name, boolean defaultValue) {
            ScriptSettings.add(this.self(), new BooleanProperty(ScriptSettings.key(name), defaultValue), name);
        }

        public void registerButton(String group, String name, boolean defaultValue) {
            this.registerGroup(group);
            this.registerButton(name, defaultValue);
        }

        public void registerKey(String name, int defaultKey) {
            ScriptSettings.add(this.self(), new KeyProperty(ScriptSettings.key(name), defaultKey), name);
        }

        public void registerKey(String group, String name, int defaultKey) {
            this.registerGroup(group);
            this.registerKey(name, defaultKey);
        }

        public void registerSlider(String name, double defaultValue, double minimum, double maximum, double interval) {
            this.registerSlider(name, "", defaultValue, minimum, maximum, interval);
        }

        public void registerSlider(String name, String suffix, double defaultValue, double minimum, double maximum,
                                   double interval) {
            ScriptEntry entry = this.self();
            String key = ScriptSettings.key(name);
            float low = (float) Math.min(minimum, maximum);
            float high = (float) Math.max(minimum, maximum);
            float start = (float) Math.max(low, Math.min(high, defaultValue));
            String label = suffix == null || suffix.isEmpty() ? name : name + " (" + suffix.trim() + ")";
            ScriptSettings.add(entry, new FloatProperty(key, start, low, high), label);
            ScriptSettings.step(entry, key, interval);
        }

        public void registerSlider(String group, String name, String suffix, double defaultValue, double minimum,
                                   double maximum, double interval) {
            this.registerGroup(group);
            this.registerSlider(name, suffix, defaultValue, minimum, maximum, interval);
        }

        public void registerSlider(String name, int defaultValue, String[] stringArray) {
            this.registerSlider(name, "", defaultValue, stringArray);
        }

        public void registerSlider(String name, String suffix, int defaultValue, String[] stringArray) {
            String[] modes = stringArray == null || stringArray.length == 0 ? new String[]{"-"} : stringArray;
            int start = Math.max(0, Math.min(modes.length - 1, defaultValue));
            ScriptSettings.add(this.self(), new ModeProperty(ScriptSettings.key(name), start, modes), name);
        }

        public void registerSlider(String group, String name, String suffix, int defaultValue, String[] stringArray) {
            this.registerGroup(group);
            this.registerSlider(name, suffix, defaultValue, stringArray);
        }

        public void registerColor(String name, int red, int green, int blue) {
            this.registerColor(name, red, green, blue, 255);
        }

        public void registerColor(String name, int red, int green, int blue, int alpha) {
            ScriptEntry entry = this.self();
            String key = ScriptSettings.key(name);
            int rgb = (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue);
            ScriptSettings.add(entry, new ColorProperty(key, rgb), name);
            Property<?> opacity = ScriptSettings.add(entry, new IntProperty(key + "-alpha", clamp(alpha), 0, 255),
                    name + " alpha");
            ScriptSettings.alpha(entry, key, opacity);
        }

        public void registerColor(String group, String name, int red, int green, int blue) {
            this.registerGroup(group);
            this.registerColor(name, red, green, blue);
        }

        public void registerColor(String group, String name, int red, int green, int blue, int alpha) {
            this.registerGroup(group);
            this.registerColor(name, red, green, blue, alpha);
        }

        private static int clamp(int value) {
            return Math.max(0, Math.min(255, value));
        }

        private static Property<?> setting(String moduleName, String name) {
            return ScriptSettings.find(find(moduleName), name);
        }

        public int getColor(String moduleName, String name) {
            Property<?> colour = setting(moduleName, name);
            if (colour == null || !(colour.getValue() instanceof Integer)) {
                return 0xFFFFFFFF;
            }
            int alpha = 255;
            ScriptEntry entry = ScriptManager.of(find(moduleName));
            Property<?> opacity = ScriptSettings.alphaOf(entry, colour.getName());
            if (opacity != null && opacity.getValue() instanceof Integer) {
                alpha = (Integer) opacity.getValue();
            }
            return (alpha << 24) | ((Integer) colour.getValue() & 0xFFFFFF);
        }

        public void setColor(String moduleName, String name, int red, int green, int blue) {
            this.setColor(moduleName, name, red, green, blue, -1);
        }

        public void setColor(String moduleName, String name, int red, int green, int blue, int alpha) {
            Property<?> colour = setting(moduleName, name);
            if (colour instanceof ColorProperty) {
                colour.setValue((clamp(red) << 16) | (clamp(green) << 8) | clamp(blue));
            }
            if (alpha >= 0 && colour != null) {
                Property<?> opacity = ScriptSettings.alphaOf(ScriptManager.of(find(moduleName)), colour.getName());
                if (opacity != null) {
                    opacity.setValue(clamp(alpha));
                }
            }
        }

        public boolean getButton(String moduleName, String name) {
            Property<?> button = setting(moduleName, name);
            return button != null && Boolean.TRUE.equals(button.getValue());
        }

        public double getSlider(String moduleName, String name) {
            Property<?> slider = setting(moduleName, name);
            if (slider == null) {
                return 0.0;
            }
            Object value = slider.getValue();
            if (value instanceof Number) {
                return ScriptSettings.stepped(ScriptManager.of(find(moduleName)), slider, ((Number) value).doubleValue());
            }
            return value instanceof Boolean ? ((Boolean) value ? 1.0 : 0.0) : 0.0;
        }

        public boolean getKeyPressed(String moduleName, String name) {
            Property<?> key = setting(moduleName, name);
            return key instanceof KeyProperty && mc.currentScreen == null && ((KeyProperty) key).isDown();
        }

        public void setButton(String moduleName, String name, boolean value) {
            Property<?> button = setting(moduleName, name);
            if (button != null) {
                button.setValue(value);
            }
        }

        public void setSlider(String moduleName, String name, double value) {
            Property<?> slider = setting(moduleName, name);
            if (slider instanceof FloatProperty) {
                slider.setValue((float) value);
            } else if (slider instanceof IntProperty || slider instanceof ModeProperty) {
                slider.setValue((int) Math.round(value));
            } else if (slider != null && slider.getValue() instanceof Double) {
                slider.setValue(value);
            }
        }

        public void setKey(String moduleName, String name, int code) {
            Property<?> key = setting(moduleName, name);
            if (key instanceof KeyProperty) {
                key.setValue(code);
            }
        }
    }

    public static class gl {
        private static void toggle(int cap, boolean on) {
            if (on) {
                GL11.glEnable(cap);
            } else {
                GL11.glDisable(cap);
            }
        }

        public static void alpha(boolean alpha) {
            if (alpha) {
                GlStateManager.enableAlpha();
            } else {
                GlStateManager.disableAlpha();
            }
        }

        public static void begin(int mode) {
            GL11.glBegin(mode);
        }

        public static void blend(boolean blend) {
            if (blend) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
        }

        public static void color(float r, float g, float b, float a) {
            GlStateManager.color(r, g, b, a);
        }

        public static void cull(boolean cull) {
            if (cull) {
                GlStateManager.enableCull();
            } else {
                GlStateManager.disableCull();
            }
        }

        public static void depth(boolean depth) {
            if (depth) {
                GlStateManager.enableDepth();
            } else {
                GlStateManager.disableDepth();
            }
        }

        public static void depthMask(boolean depthMask) {
            GlStateManager.depthMask(depthMask);
        }

        public static void disable(int cap) {
            GL11.glDisable(cap);
        }

        public static void disableItemLighting() {
            RenderHelper.disableStandardItemLighting();
        }

        public static void enable(int cap) {
            GL11.glEnable(cap);
        }

        public static void enableItemLighting(boolean gui) {
            if (gui) {
                RenderHelper.enableGUIStandardItemLighting();
            } else {
                RenderHelper.enableStandardItemLighting();
            }
        }

        public static void resetColor() {
            GlStateManager.resetColor();
        }

        public static void end() {
            GL11.glEnd();
        }

        public static void lighting(boolean lighting) {
            if (lighting) {
                GlStateManager.enableLighting();
            } else {
                GlStateManager.disableLighting();
            }
        }

        public static void lineSmooth(boolean lineSmooth) {
            toggle(GL11.GL_LINE_SMOOTH, lineSmooth);
        }

        public static void lineWidth(float width) {
            GL11.glLineWidth(width);
        }

        public static void normal(float x, float y, float z) {
            GL11.glNormal3f(x, y, z);
        }

        public static void pop() {
            GlStateManager.popMatrix();
        }

        public static void push() {
            GlStateManager.pushMatrix();
        }

        public static void rotate(float angle, float x, float y, float z) {
            GlStateManager.rotate(angle, x, y, z);
        }

        public static void scale(float x, float y, float z) {
            GlStateManager.scale(x, y, z);
        }

        public static void scissor(int x, int y, int width, int height) {
            GL11.glScissor(x, y, width, height);
        }

        public static void scissor(boolean scissor) {
            toggle(GL11.GL_SCISSOR_TEST, scissor);
        }

        public static void texture2d(boolean texture2d) {
            if (texture2d) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
            }
        }

        public static void translate(float x, float y, float z) {
            GlStateManager.translate(x, y, z);
        }

        public static void vertex2(float x, float y) {
            GL11.glVertex2f(x, y);
        }

        public static void vertex3(float x, float y, float z) {
            GL11.glVertex3f(x, y, z);
        }
    }

    public static class config {
        private static final File FILE = new File(ScriptManager.ROOT, "config.json");
        private static JsonObject values;

        private static synchronized JsonObject values() {
            if (values == null) {
                values = new JsonObject();
                try {
                    if (FILE.isFile()) {
                        JsonElement element = new JsonParser().parse(
                                new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8));
                        if (element.isJsonObject()) {
                            values = element.getAsJsonObject();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            return values;
        }

        public static synchronized boolean set(String key, final String value) {
            if (key == null || key.isEmpty()) {
                return false;
            }
            JsonObject all = values();
            if (value == null) {
                all.remove(key);
            } else {
                all.addProperty(key, value);
            }
            try {
                FILE.getParentFile().mkdirs();
                Files.write(FILE.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(all)
                        .getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        public static synchronized String get(final String key) {
            JsonObject all = values();
            return key != null && all.has(key) && all.get(key).isJsonPrimitive() ? all.get(key).getAsString() : null;
        }
    }

    public static class render {
        private static final FloatBuffer SCREEN = BufferUtils.createFloatBuffer(3);

        private static float[] argb(int color) {
            return new float[]{(color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F, (color & 0xFF) / 255.0F,
                    (color >>> 24) / 255.0F};
        }

        private static void box(AxisAlignedBB box, int color, boolean outline, boolean shade) {
            float[] c = argb(color);
            GlStateManager.pushMatrix();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.disableTexture2D();
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GL11.glLineWidth(2.0F);
            if (outline) {
                GlStateManager.color(c[0], c[1], c[2], c[3]);
                RenderGlobal.drawSelectionBoundingBox(box);
            }
            if (shade) {
                atlas.util.RenderUtil.drawFilledBoundingBox(box, c[0], c[1], c[2], 0.25F);
            }
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.popMatrix();
        }

        public static void block(Vec3 position, int color, boolean outline, boolean shade) {
            block((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z), color, outline, shade);
        }

        public static void block(int x, int y, int z, int color, boolean outline, boolean shade) {
            atlas.util.RenderUtil.renderBlock(new BlockPos(x, y, z), color, outline, shade);
        }

        public static void entity(Entity en, int color, float partialTicks, boolean outline, boolean shade) {
            net.minecraft.entity.Entity entity = en.entity;
            double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - entity.posX
                    - mc.getRenderManager().viewerPosX;
            double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - entity.posY
                    - mc.getRenderManager().viewerPosY;
            double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - entity.posZ
                    - mc.getRenderManager().viewerPosZ;
            box(entity.getEntityBoundingBox().offset(x, y, z), color, outline, shade);
        }

        public static void entityGui(Entity en, int x, int y, float mouseX, float mouseY, int scale) {
            if (en != null && en.entity instanceof EntityLivingBase) {
                GuiInventory.drawEntityOnScreen(x, y, scale, mouseX, mouseY, (EntityLivingBase) en.entity);
            }
        }

        public static void resetEquippedProgress() {
            mc.getItemRenderer().resetEquippedProgress();
        }

        public static void tracer(Entity entity, float lineWidth, int color, float partialTicks) {
            net.minecraft.entity.Entity target = entity.entity;
            double x = target.lastTickPosX + (target.posX - target.lastTickPosX) * partialTicks - mc.getRenderManager().viewerPosX;
            double y = target.lastTickPosY + (target.posY - target.lastTickPosY) * partialTicks
                    - mc.getRenderManager().viewerPosY + target.height / 2.0;
            double z = target.lastTickPosZ + (target.posZ - target.lastTickPosZ) * partialTicks - mc.getRenderManager().viewerPosZ;
            net.minecraft.util.Vec3 eyes = new net.minecraft.util.Vec3(0.0, 0.0, 1.0)
                    .rotatePitch(-(float) Math.toRadians(mc.thePlayer.rotationPitch))
                    .rotateYaw(-(float) Math.toRadians(mc.thePlayer.rotationYaw));
            lineRelative(eyes.xCoord, eyes.yCoord + mc.thePlayer.getEyeHeight(), eyes.zCoord, x, y, z, lineWidth, color);
        }

        public static void showGui() {
            mc.displayGuiScreen(new GuiScreen() {
            });
        }

        public static void item(ItemStack item, float x, float y, float scale) {
            if (item == null || item.itemStack == null) {
                return;
            }
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            RenderHelper.enableGUIStandardItemLighting();
            mc.getRenderItem().renderItemAndEffectIntoGUI(item.itemStack, 0, 0);
            mc.getRenderItem().renderItemOverlays(mc.fontRendererObj, item.itemStack, 0, 0);
            RenderHelper.disableStandardItemLighting();
            GlStateManager.popMatrix();
        }

        public static void image(Image image, float x, float y, float width, float height) {
            if (image == null || !image.isLoaded() || image.textureId < 0) {
                return;
            }
            GlStateManager.enableBlend();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.bindTexture(image.textureId);
            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer buffer = tessellator.getWorldRenderer();
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            buffer.pos(x, y + height, 0.0).tex(0.0, 1.0).endVertex();
            buffer.pos(x + width, y + height, 0.0).tex(1.0, 1.0).endVertex();
            buffer.pos(x + width, y, 0.0).tex(1.0, 0.0).endVertex();
            buffer.pos(x, y, 0.0).tex(0.0, 0.0).endVertex();
            tessellator.draw();
        }

        public static Vec3 worldToScreen(double x, double y, double z, int scaleFactor, float partialTicks) {
            if (!RavenHooks.matrices) {
                return null;
            }
            SCREEN.clear();
            boolean ok = GLU.gluProject((float) (x - mc.getRenderManager().viewerPosX),
                    (float) (y - mc.getRenderManager().viewerPosY), (float) (z - mc.getRenderManager().viewerPosZ),
                    RavenHooks.MODELVIEW, RavenHooks.PROJECTION, RavenHooks.VIEWPORT, SCREEN);
            if (!ok) {
                return null;
            }
            int scale = Math.max(1, scaleFactor);
            return new Vec3(SCREEN.get(0) / scale, (Display.getHeight() - SCREEN.get(1)) / scale, SCREEN.get(2));
        }

        public static void roundedRect(float startX, float startY, float endX, float endY, float radius, int color) {
            atlas.util.RenderUtil.drawRoundedRect(startX, startY, endX - startX, endY - startY, radius, color);
        }

        public static void gradientRect(float startX, float startY, float endX, float endY, int leftColor, int rightColor) {
            gradientRect(startX, startY, endX, endY, leftColor, leftColor, rightColor, rightColor);
        }

        public static void gradientRect(float startX, float startY, float endX, float endY, int topLeftColor,
                                        int bottomLeftColor, int topRightColor, int bottomRightColor) {
            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.disableAlpha();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.shadeModel(GL11.GL_SMOOTH);
            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer buffer = tessellator.getWorldRenderer();
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            vertex(buffer, endX, startY, topRightColor);
            vertex(buffer, startX, startY, topLeftColor);
            vertex(buffer, startX, endY, bottomLeftColor);
            vertex(buffer, endX, endY, bottomRightColor);
            tessellator.draw();
            GlStateManager.shadeModel(GL11.GL_FLAT);
            GlStateManager.enableAlpha();
            GlStateManager.enableTexture2D();
        }

        private static void vertex(WorldRenderer buffer, float x, float y, int color) {
            buffer.pos(x, y, 0.0).color(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, color >>> 24).endVertex();
        }

        public static double[] getRotations() {
            return new double[]{mc.getRenderManager().playerViewY, mc.getRenderManager().playerViewX};
        }

        public static double[] getCameraRotations() {
            net.minecraft.entity.Entity view = mc.getRenderViewEntity() == null ? mc.thePlayer : mc.getRenderViewEntity();
            boolean front = mc.gameSettings.thirdPersonView == 2;
            return new double[]{view.rotationYaw + (front ? 180.0F : 0.0F), front ? -view.rotationPitch : view.rotationPitch};
        }

        public static int getFontWidth(String text) {
            int extra = 0;
            boolean bold = false;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '§' && i + 1 < text.length()) {
                    bold |= Character.toLowerCase(text.charAt(++i)) == 'l';
                } else if (bold) {
                    extra++;
                }
            }
            return mc.fontRendererObj.getStringWidth(text) + extra;
        }

        public static int getFontHeight() {
            return mc.fontRendererObj.FONT_HEIGHT;
        }

        public static Vec3 getPosition() {
            return new Vec3(mc.getRenderManager().viewerPosX, mc.getRenderManager().viewerPosY,
                    mc.getRenderManager().viewerPosZ);
        }

        public static void text2d(String text, float x, float y, float scale, int color, boolean shadow) {
            GlStateManager.pushMatrix();
            if (scale != 1.0F) {
                GlStateManager.scale(scale, scale, scale);
            }
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            mc.fontRendererObj.drawString(text, x / scale, y / scale, color, shadow);
            GlStateManager.disableBlend();
            GlStateManager.popMatrix();
        }

        public static void text3d(String text, Vec3 position, float scale, boolean shadow, boolean depth,
                                  boolean background, int color) {
            double x = position.x - mc.getRenderManager().viewerPosX;
            double y = position.y - mc.getRenderManager().viewerPosY;
            double z = position.z - mc.getRenderManager().viewerPosZ;
            float size = 0.02666667F * scale;
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, z);
            GL11.glNormal3f(0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(-mc.getRenderManager().playerViewY, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(mc.getRenderManager().playerViewX * (mc.gameSettings.thirdPersonView == 2 ? -1.0F : 1.0F),
                    1.0F, 0.0F, 0.0F);
            GlStateManager.scale(-size, -size, size);
            GlStateManager.disableLighting();
            if (!depth) {
                GlStateManager.disableDepth();
                GlStateManager.depthMask(false);
            }
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            int half = mc.fontRendererObj.getStringWidth(text) / 2;
            if (background) {
                GlStateManager.disableTexture2D();
                Tessellator tessellator = Tessellator.getInstance();
                WorldRenderer buffer = tessellator.getWorldRenderer();
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
                buffer.pos(-half - 1, -1, 0.0).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
                buffer.pos(-half - 1, 8, 0.0).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
                buffer.pos(half + 1, 8, 0.0).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
                buffer.pos(half + 1, -1, 0.0).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
                tessellator.draw();
                GlStateManager.enableTexture2D();
            }
            mc.fontRendererObj.drawString(text, -half, 0, color, shadow);
            if (!depth) {
                GlStateManager.depthMask(true);
                GlStateManager.enableDepth();
            }
            GlStateManager.enableLighting();
            GlStateManager.disableBlend();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }

        public static void rect(float startX, float startY, float endX, float endY, final int color) {
            atlas.util.RenderUtil.drawRect((double) startX, (double) startY, (double) endX, (double) endY, color);
        }

        public static void line2D(double startX, double startY, double endX, double endY, float lineWidth, int color) {
            atlas.util.RenderUtil.drawLine((float) startX, (float) startY, (float) endX, (float) endY, lineWidth, color);
        }

        public static void line3D(Vec3 pos1, Vec3 pos2, float lineWidth, int color) {
            line3D(pos1.x, pos1.y, pos1.z, pos2.x, pos2.y, pos2.z, lineWidth, color);
        }

        public static void line3D(final double startX, final double startY, final double startZ, double endX,
                                  double endY, double endZ, final float lineWidth, final int color) {
            double vx = mc.getRenderManager().viewerPosX;
            double vy = mc.getRenderManager().viewerPosY;
            double vz = mc.getRenderManager().viewerPosZ;
            lineRelative(startX - vx, startY - vy, startZ - vz, endX - vx, endY - vy, endZ - vz, lineWidth, color);
        }

        private static void lineRelative(double x1, double y1, double z1, double x2, double y2, double z2,
                                         float lineWidth, int color) {
            float[] c = argb(color);
            GlStateManager.pushMatrix();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.disableTexture2D();
            GlStateManager.disableDepth();
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(lineWidth);
            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer buffer = tessellator.getWorldRenderer();
            buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            buffer.pos(x1, y1, z1).color(c[0], c[1], c[2], c[3]).endVertex();
            buffer.pos(x2, y2, z2).color(c[0], c[1], c[2], c[3]).endVertex();
            tessellator.draw();
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(1.0F);
            GlStateManager.enableDepth();
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.popMatrix();
        }

        public static boolean isInView(Entity en) {
            Frustum frustum = new Frustum();
            frustum.setPosition(mc.getRenderManager().viewerPosX, mc.getRenderManager().viewerPosY,
                    mc.getRenderManager().viewerPosZ);
            return frustum.isBoundingBoxInFrustum(en.entity.getEntityBoundingBox());
        }

        public static class blur {
            private static Framebuffer mask;

            public static void prepare() {
                mask = atlas.util.RenderUtil.createFrameBuffer(mask);
                mask.framebufferClear();
                mask.bindFramebuffer(false);
            }

            public static void apply(final int passes, final float radius) {
                mc.getFramebuffer().bindFramebuffer(false);
                if (mask != null) {
                    atlas.util.shader.KawaseBlur.renderBlur(mask.framebufferTexture, Math.max(1, passes), radius);
                }
            }
        }

        public static class bloom {
            private static Framebuffer source;

            public static void prepare() {
                source = atlas.util.RenderUtil.createFrameBuffer(source);
                source.framebufferClear();
                source.bindFramebuffer(false);
            }

            public static void apply(final int passes, final float radius) {
                mc.getFramebuffer().bindFramebuffer(false);
                if (source != null) {
                    atlas.util.shader.KawaseBloom.renderBlur(source.framebufferTexture, Math.max(1, passes), radius);
                }
            }
        }
    }

    public static class inventory {
        public static int getSlot() {
            return mc.thePlayer.inventory.currentItem;
        }

        public static void setSlot(int slot) {
            if (slot >= 0 && slot <= 8) {
                mc.thePlayer.inventory.currentItem = slot;
            }
        }

        public static void click(int slot, int button, int mode) {
            mc.playerController.windowClick(mc.thePlayer.openContainer.windowId, slot, button, mode, mc.thePlayer);
        }

        public static List<String> getBookContents() {
            List<String> out = new ArrayList<String>();
            if (!(mc.currentScreen instanceof GuiScreenBook)) {
                return out;
            }
            Object pages = field(mc.currentScreen, GuiScreenBook.class, "field_146483_y", "bookPages");
            if (pages instanceof NBTTagList) {
                NBTTagList list = (NBTTagList) pages;
                for (int i = 0; i < list.tagCount(); i++) {
                    String page = list.getStringTagAt(i);
                    try {
                        IChatComponent text = IChatComponent.Serializer.jsonToComponent(page);
                        out.add(text == null ? page : text.getUnformattedText());
                    } catch (Exception e) {
                        out.add(page);
                    }
                }
            }
            return out;
        }

        public static String getChest() {
            if (mc.thePlayer.openContainer instanceof ContainerChest) {
                return ((ContainerChest) mc.thePlayer.openContainer).getLowerChestInventory().getDisplayName()
                        .getUnformattedText();
            }
            return "";
        }

        public static String getContainer() {
            if (mc.currentScreen instanceof GuiContainerCreative) {
                CreativeTabs tab = CreativeTabs.creativeTabArray[((GuiContainerCreative) mc.currentScreen).getSelectedTabIndex()];
                return tab == null ? "" : net.minecraft.client.resources.I18n.format(tab.getTranslatedTabLabel());
            }
            if (mc.currentScreen instanceof GuiInventory) {
                return net.minecraft.client.resources.I18n.format("container.crafting");
            }
            String chest = getChest();
            if (!chest.isEmpty() || mc.currentScreen == null) {
                return chest;
            }
            return mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainer
                    ? mc.currentScreen.getClass().getSimpleName() : "";
        }

        public static int getSize() {
            return mc.thePlayer.inventory.getSizeInventory();
        }

        public static int getChestSize() {
            if (mc.thePlayer.openContainer instanceof ContainerChest) {
                return ((ContainerChest) mc.thePlayer.openContainer).getLowerChestInventory().getSizeInventory();
            }
            return -1;
        }

        public static ItemStack getStackInSlot(int slot) {
            return ItemStack.convert(mc.thePlayer.inventory.getStackInSlot(slot));
        }

        public static ItemStack getStackInChestSlot(int slot) {
            if (mc.thePlayer.openContainer instanceof ContainerChest) {
                return ItemStack.convert(((ContainerChest) mc.thePlayer.openContainer).getLowerChestInventory()
                        .getStackInSlot(slot));
            }
            return null;
        }

        public static ItemStack getStackInCraftingSlot(int slot) {
            ContainerPlayer container = (ContainerPlayer) mc.thePlayer.inventoryContainer;
            return slot >= 0 && slot < 4 ? ItemStack.convert(container.craftMatrix.getStackInSlot(slot)) : null;
        }

        public static ItemStack getCraftResult() {
            ContainerPlayer container = (ContainerPlayer) mc.thePlayer.inventoryContainer;
            return ItemStack.convert(container.craftResult.getStackInSlot(0));
        }

        public static void open() {
            mc.getNetHandler().addToSendQueue(new C16PacketClientStatus(C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT));
            mc.displayGuiScreen(new GuiInventory(mc.thePlayer));
        }
    }

    public static class keybinds {
        private static KeyBinding binding(String key) {
            if (key == null) {
                return null;
            }
            for (KeyBinding binding : mc.gameSettings.keyBindings) {
                String name = binding.getKeyDescription();
                if (name.equals("key." + key) || name.equals(key)) {
                    return binding;
                }
            }
            return null;
        }

        public static int[] getMousePosition() {
            return new int[]{Mouse.getX(), Mouse.getY()};
        }

        public static boolean isPressed(final String key) {
            KeyBinding binding = binding(key);
            return binding != null && binding.isKeyDown();
        }

        public static void setPressed(final String key, final boolean pressed) {
            KeyBinding binding = binding(key);
            if (binding != null) {
                KeyBinding.setKeyBindState(binding.getKeyCode(), pressed);
            }
        }

        public static void onTick(final String key) {
            KeyBinding binding = binding(key);
            if (binding != null) {
                KeyBinding.onTick(binding.getKeyCode());
            }
        }

        public static int getKeyCode(final String key) {
            KeyBinding binding = binding(key);
            return binding == null ? -1 : binding.getKeyCode();
        }

        public static int getKeyIndex(String key) {
            return Keyboard.getKeyIndex(key == null ? "" : key.toUpperCase(Locale.ROOT));
        }

        public static boolean isMouseDown(int mouseButton) {
            return Mouse.isButtonDown(mouseButton);
        }

        public static boolean isKeyDown(int key) {
            return Keyboard.isKeyDown(key);
        }

        public static void rightClick() {
            ((IAccessorMinecraft) mc).callRightClickMouse();
        }

        public static void leftClick() {
            ((IAccessorMinecraft) mc).callClickMouse();
        }

        public static int getScroll() {
            return Mouse.getDWheel();
        }
    }

    public static class util {
        public static final String colorSymbol = "§";

        public static String color(String message) {
            return message == null ? "" : message.replace("&", colorSymbol);
        }

        public static String strip(String string) {
            return string == null ? "" : string.replaceAll("(?i)§[0-9a-fk-or]", "");
        }

        public static double round(double value, int decimals) {
            double scale = Math.pow(10.0, decimals);
            return Math.round(value * scale) / scale;
        }

        public static int randomInt(int min, int max) {
            int low = Math.min(min, max);
            int high = Math.max(min, max);
            return low + RANDOM.nextInt(high - low + 1);
        }

        public static double randomDouble(double min, double max) {
            return min + RANDOM.nextDouble() * (max - min);
        }
    }
}
