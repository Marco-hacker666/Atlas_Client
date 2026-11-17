package atlas.management;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UsageReporterTest {

    @Test
    public void theProofIdIsThePublicHashTheRelayWorksOutToo() throws Exception {
        /* sha1("atlas-proof\nsid-1\nsteve\n1700000000"), as report-relay/src/verify.js proofServerId makes it. */
        assertEquals("61af13b664dc4926767746e7a606b1dc4754f259", UsageReporter.proofId("sid-1", "Steve", 1700000000L));
        assertEquals(UsageReporter.proofId("sid-1", "steve", 1L), UsageReporter.proofId("sid-1", "STEVE", 1L));
    }

    @Test
    public void publicNamesThatPassForStaffAreRefused() {
        assertTrue(UsageReporter.validAlias("Sky Walker"));
        assertTrue(UsageReporter.validAlias("流星"));
        assertFalse(UsageReporter.validAlias("Admin"));
        assertFalse(UsageReporter.validAlias("x"));
        assertFalse(UsageReporter.validAlias("<script>"));
        assertFalse(UsageReporter.validAlias("Atlas Team"));
    }
}
