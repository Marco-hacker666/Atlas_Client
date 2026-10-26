package atlas.util;

import net.minecraft.util.ResourceLocation;
import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Constructor;

import static org.junit.Assert.*;

/**
 * 2026-10-11: every "atlas:" font and image the code asks for resolves through
 * the jar's own resource pack (AtlasResources). Before it, all 16 fonts failed
 * in game because the jar had no resource pack at all.
 */
public class AtlasResourcesTest {

    private static AtlasResources pack() throws Exception {
        Constructor<AtlasResources> c = AtlasResources.class.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    @Test
    public void everyFontTheClientLoadsIsServed() throws Exception {
        AtlasResources pack = pack();
        String[] fonts = {"regular.ttf", "icon.ttf", "product_sans_regular.ttf", "product_sans_light.ttf",
                "product_sans_medium.ttf", "Google-Sans.ttf", "San-Francisco-Pro-Fonts.ttf", "nunito.ttf",
                "Nunito-Bold.ttf", "harmonyOS_Sans.ttf", "NotoSansSC-Regular.ttf", "tenacity.ttf",
                "tenacity-bold.ttf", "Vision.otf", "tahomabold.ttf", "noti.ttf", "nbp-informa-fivesix.ttf"};
        for (String font : fonts) {
            ResourceLocation location = new ResourceLocation("atlas:font/" + font);
            assertTrue(font, pack.resourceExists(location));
            try (InputStream in = pack.getInputStream(location)) {
                assertTrue(font, in.read() >= 0);
            }
        }
    }

    @Test
    public void logoIsServed() throws Exception {
        AtlasResources pack = pack();
        assertTrue(pack.resourceExists(new ResourceLocation("atlas", "assets/atlas-logo.png")));
        /* 2026-10-26: another client's wordmark images were removed with the WaterMark mode that drew them. */
        assertFalse(pack.resourceExists(new ResourceLocation("atlas", "assets/textvape.png")));
        assertFalse(pack.resourceExists(new ResourceLocation("atlas", "assets/textv4.png")));
    }

    @Test
    public void onlyTheAtlasDomain() throws Exception {
        AtlasResources pack = pack();
        assertEquals(1, pack.getResourceDomains().size());
        assertTrue(pack.getResourceDomains().contains("atlas"));
        assertFalse(pack.resourceExists(new ResourceLocation("minecraft", "font/regular.ttf")));
    }
}
