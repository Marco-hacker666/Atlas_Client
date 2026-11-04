package atlas.script.raven.model;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raven script API: a picture from a URL (or a file path) for render.image (2026-11-02). Downloaded on a thread of
 * its own; the texture is made on the client thread when it arrives. With cached, the same URL is downloaded once.
 */
public class Image {
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<String, Image>();

    public String url;
    public BufferedImage bufferedImage;
    public int height;
    public int width;
    public int textureId = -1;
    public boolean cached;
    private volatile boolean loaded;

    public Image(String url, boolean cached) {
        this.url = url;
        this.cached = cached;
        Image known = cached ? CACHE.get(url) : null;
        if (known != null) {
            this.bufferedImage = known.bufferedImage;
            this.height = known.height;
            this.width = known.width;
            this.textureId = known.textureId;
            this.loaded = known.loaded;
            if (this.loaded) {
                return;
            }
        }
        if (cached) {
            CACHE.put(url, this);
        }
        Thread thread = new Thread(this::download, "Atlas-ScriptImage");
        thread.setDaemon(true);
        thread.start();
    }

    private void download() {
        try {
            BufferedImage image;
            if (this.url.startsWith("http://") || this.url.startsWith("https://")) {
                HttpURLConnection connection = (HttpURLConnection) new URL(this.url).openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                try (InputStream in = connection.getInputStream()) {
                    image = ImageIO.read(in);
                }
            } else {
                image = ImageIO.read(new File(this.url));
            }
            if (image == null) {
                return;
            }
            final BufferedImage done = image;
            Minecraft.getMinecraft().addScheduledTask(() -> {
                this.bufferedImage = done;
                this.width = done.getWidth();
                this.height = done.getHeight();
                this.textureId = new DynamicTexture(done).getGlTextureId();
                this.loaded = true;
            });
        } catch (Exception ignored) {
            // Not loaded: render.image draws nothing for it.
        }
    }

    /** {width, height} in pixels. */
    /** Its size on the GUI, as Raven gives it: pixels over the GUI scale (whole numbers). */
    public float[] getDimensions() {
        int scale = new net.minecraft.client.gui.ScaledResolution(net.minecraft.client.Minecraft.getMinecraft())
                .getScaleFactor();
        return new float[]{this.width / scale, this.height / scale};
    }

    public boolean isLoaded() {
        return this.loaded;
    }

    public static void clearCache() {
        CACHE.clear();
    }

    @Override
    public String toString() {
        return "Image{" + this.url + (this.loaded ? ", " + this.width + "x" + this.height : ", loading") + "}";
    }
}
