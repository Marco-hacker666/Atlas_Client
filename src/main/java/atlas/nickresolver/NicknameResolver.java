package atlas.nickresolver;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public final class NicknameResolver {
    private static final Logger LOG = LogManager.getLogger("NicknameResolver");
    private static final int REFRESH_TICKS = 20 * 15;

    private static volatile NicknameResolver instance;

    private final NickSettings settings;
    private final IdentityCache cache;
    private final MojangApiClient mojang;
    private final CloudClient cloud;
    private final IdentityResolver resolver;
    private final CustomPayloadInspector payloads;
    private final PacketListener packets;
    private final SessionLog log;
    private final boolean attachNetty;
    private final ScheduledExecutorService saver;
    private int ticks;
    private NickCommand command;

    private NicknameResolver(File dir, boolean attachNetty) {
        this.attachNetty = attachNetty;
        this.settings = new NickSettings(new File(dir, "settings.cfg"));
        this.cache = new IdentityCache(new File(dir, "identity-cache.json"));
        this.cache.load();
        this.mojang = new MojangApiClient(() -> this.settings.maxRequestsPer10Min);
        this.cloud = new CloudClient(this.settings);
        this.resolver = new IdentityResolver(this.cache, this.mojang, this.settings, this.cloud);
        this.payloads = new CustomPayloadInspector(this.resolver);
        this.packets = new PacketListener(this.resolver, this.payloads, this.settings);
        this.log = new SessionLog(new File(dir, "logs"), this.settings);
        this.resolver.setLog(this.log);
        this.saver = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NicknameResolver-Save");
            t.setDaemon(true);
            return t;
        });
        this.saver.scheduleWithFixedDelay(this::saveQuietly, 60, 60, TimeUnit.SECONDS);
    }

    public static synchronized NicknameResolver install(File dir, boolean attachNetty) {
        if (instance != null) {
            return instance;
        }
        NicknameResolver core = new NicknameResolver(dir, attachNetty);
        MinecraftForge.EVENT_BUS.register(new NametagRenderer(core.resolver, core.settings));
        NickKeybinds keybinds = new NickKeybinds(core.settings);
        keybinds.register();
        FMLCommonHandler.instance().bus().register(keybinds);
        ClientCommandHandler.instance.registerCommand(new NickCommand(core));
        return core.finishInstall();
    }

    public static synchronized NicknameResolver installHosted(File dir, Predicate<EntityPlayer> hostDraws, String typed) {
        if (instance != null) {
            return instance;
        }
        NickCommand.typed = typed;
        NicknameResolver core = new NicknameResolver(dir, false);
        MinecraftForge.EVENT_BUS.register(new NametagRenderer(core.resolver, core.settings, hostDraws));
        core.command = new NickCommand(core);
        return core.finishInstall();
    }

    private NicknameResolver finishInstall() {
        MinecraftForge.EVENT_BUS.register(new ChatAnnotator(this.resolver, this.settings));
        FMLCommonHandler.instance().bus().register(this);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "NicknameResolver-Shutdown"));
        instance = this;
        LOG.info("NicknameResolver installed ({} cached identities)", this.cache.all().size());
        return this;
    }

    public NickCommand command() {
        return this.command;
    }

    public static NicknameResolver get() {
        return instance;
    }

    public void setCloudConsent(boolean consent) {
        this.settings.setToggle("cloud", consent);
        if (consent) {
            this.cloud.flushAsync();
        }
    }

    public void onPacket(Packet<?> packet) {
        this.packets.onPacket(packet);
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        this.resolver.onDisconnect();
        if (this.attachNetty) {
            this.packets.attach(event.manager);
        }
        ServerData server = Minecraft.getMinecraft().getCurrentServerData();
        this.cloud.setServer(event.isLocal || server == null ? null : server.serverIP);
        this.log.connect(event.isLocal || server == null ? null : server.serverIP);
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        this.log.disconnect(this.cache, this.payloads.channels(), this.cloud);
        this.resolver.onDisconnect();
        this.cloud.setServer(null);
        this.saveAsync();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++this.ticks < REFRESH_TICKS) {
            return;
        }
        this.ticks = 0;
        this.resolver.refreshOnline();
        this.log.sweep(this.resolver.onlineIdentities());
    }

    public void saveAsync() {
        if (!this.saver.isShutdown()) {
            this.saver.execute(this::saveQuietly);
        }
    }

    private void saveQuietly() {
        try {
            this.cache.saveIfDirty();
        } catch (RuntimeException e) {
            LOG.warn("Saving the identity cache failed", e);
        }
    }

    private void shutdown() {
        this.mojang.shutdown();
        this.cloud.shutdown();
        this.saver.shutdownNow();
        this.saveQuietly();
        this.log.shutdown();
    }

    public NickSettings settings() {
        return this.settings;
    }

    public IdentityCache cache() {
        return this.cache;
    }

    public IdentityResolver resolver() {
        return this.resolver;
    }

    public MojangApiClient mojang() {
        return this.mojang;
    }

    public CloudClient cloud() {
        return this.cloud;
    }

    public CustomPayloadInspector payloads() {
        return this.payloads;
    }
}
