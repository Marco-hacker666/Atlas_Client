package myau.util;

import net.minecraft.util.ResourceLocation;
import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Constructor;

import static org.junit.Assert.*;

/**
 * 2026-10-11: every "myau:" font and image the code asks for resolves through
 * the jar's own resource pack (MyauResources). Before it, all 16 fonts failed
 * in game because the jar had no resource pack at all.
 */
public class MyauResourcesTest {

    private static MyauResources pack() throws Exception {
        Constructor<MyauResources> c = MyauResources.class.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    @Test
    public void everyFontTheClientLoadsIsServed() throws Exception {
        MyauResources pack = pack();
        String[] fonts = {"regular.ttf", "icon.ttf", "product_sans_regular.ttf", "product_sans_light.ttf",
                "product_sans_medium.ttf", "Google-Sans.ttf", "San-Francisco-Pro-Fonts.ttf", "nunito.ttf",
                "Nunito-Bold.ttf", "harmonyOS_Sans.ttf", "NotoSansSC-Regular.ttf", "tenacity.ttf",
                "tenacity-bold.ttf", "Vision.otf", "tahomabold.ttf", "noti.ttf", "nbp-informa-fivesix.ttf"};
        for (String font : fonts) {
            ResourceLocation location = new ResourceLocation("myau:font/" + font);
            assertTrue(font, pack.resourceExists(location));
            try (InputStream in = pack.getInputStream(location)) {
                assertTrue(font, in.read() >= 0);
            }
        }
    }

    @Test
    public void watermarkImagesAreServed() throws Exception {
        MyauResources pack = pack();
        assertTrue(pack.resourceExists(new ResourceLocation("myau", "assets/textvape.png")));
        assertTrue(pack.resourceExists(new ResourceLocation("myau", "assets/textv4.png")));
    }

    @Test
    public void onlyTheMyauDomain() throws Exception {
        MyauResources pack = pack();
        assertEquals(1, pack.getResourceDomains().size());
        assertTrue(pack.getResourceDomains().contains("myau"));
        assertFalse(pack.resourceExists(new ResourceLocation("minecraft", "font/regular.ttf")));
    }
}
