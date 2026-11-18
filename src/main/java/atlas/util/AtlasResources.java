package atlas.util;

import atlas.Atlas;
import atlas.mixin.IAccessorMinecraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.client.resources.data.IMetadataSerializer;
import net.minecraft.util.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class AtlasResources implements IResourcePack {
    private static final Set<String> DOMAINS = Collections.singleton("atlas");
    private static boolean registered;

    private AtlasResources() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            AtlasResources pack = new AtlasResources();
            List<IResourcePack> defaults = ((IAccessorMinecraft) mc).getDefaultResourcePacks();
            if (defaults != null) {
                defaults.add(pack);
            }
            IResourceManager manager = mc.getResourceManager();
            if (manager instanceof SimpleReloadableResourceManager) {
                ((SimpleReloadableResourceManager) manager).reloadResourcePack(pack);
            }
        } catch (Throwable t) {
            System.err.println("[Atlas] could not register the jar's resources: " + t);
        }
    }

    private static String path(ResourceLocation location) {
        return "/assets/" + location.getResourceDomain() + "/" + location.getResourcePath();
    }

    @Override
    public InputStream getInputStream(ResourceLocation location) throws IOException {
        InputStream in = Atlas.class.getResourceAsStream(path(location));
        if (in == null) {
            throw new FileNotFoundException(location.toString());
        }
        return in;
    }

    @Override
    public boolean resourceExists(ResourceLocation location) {
        return DOMAINS.contains(location.getResourceDomain()) && Atlas.class.getResource(path(location)) != null;
    }

    @Override
    public Set<String> getResourceDomains() {
        return DOMAINS;
    }

    @Override
    public <T extends IMetadataSection> T getPackMetadata(IMetadataSerializer serializer, String section) {
        return null;
    }

    @Override
    public BufferedImage getPackImage() throws IOException {
        throw new FileNotFoundException("pack.png");
    }

    @Override
    public String getPackName() {
        return "Atlas";
    }
}
