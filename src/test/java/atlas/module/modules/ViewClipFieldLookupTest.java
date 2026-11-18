package atlas.module.modules;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ViewClipFieldLookupTest {
    @SuppressWarnings("unused")
    private static class ParentRenderer {
        private float thirdPersonDistance = 4.0F;
        private float field_78430_p = 4.0F;
    }

    private static class ChildRenderer extends ParentRenderer {
    }

    private static Field findField(Class<?> cls, String deobf, String searge) throws Exception {
        Method method = ViewClip.class.getDeclaredMethod("findField", Class.class, String.class, String.class);
        method.setAccessible(true);
        return (Field) method.invoke(null, cls, deobf, searge);
    }

    @Test
    public void aFieldInheritedFromASuperclassIsFound() throws Exception {
        Field field = findField(ChildRenderer.class, "thirdPersonDistance", "field_78430_p");

        assertNotNull("an inherited field must be found, not missed", field);
        ChildRenderer renderer = new ChildRenderer();
        assertEquals("a superclass Field must read the subclass instance",
                4.0F, field.getFloat(renderer), 0.0F);

        field.setFloat(renderer, 9.0F);
        ParentRenderer parent = renderer;
        assertEquals("and write it", 9.0F, parent.thirdPersonDistance, 0.0F);
    }

    @Test
    public void theDeobfuscatedNameWinsOverTheSrgName() throws Exception {
        Field field = findField(ChildRenderer.class, "thirdPersonDistance", "field_78430_p");

        assertNotNull(field);
        assertSameField("the deobfuscated name is tried first", "thirdPersonDistance", field);
    }

    @Test
    public void theSrgNameIsUsedWhenTheDeobfuscatedNameIsAbsent() throws Exception {
        Field field = findField(ChildRenderer.class, "no-such-deobf-name", "field_78430_p");

        assertNotNull("a production (SRG) build must still resolve", field);
        assertSameField("the SRG name is the fallback", "field_78430_p", field);
    }

    private static void assertSameField(String message, String expectedName, Field field) {
        assertEquals(message, expectedName, field.getName());
        assertEquals(message, ParentRenderer.class, field.getDeclaringClass());
    }

    @Test
    public void aNameThatExistsNowhereReturnsNullInsteadOfThrowing() throws Exception {
        assertNull(findField(ChildRenderer.class, "no-such-deobf-name", "no-such-srg-name"));
    }
}
