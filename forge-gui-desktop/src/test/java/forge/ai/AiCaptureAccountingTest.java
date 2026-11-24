package forge.ai;

import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.Map;

public class AiCaptureAccountingTest {
    @Test(groups = {"UnitTest", "fast"})
    public void closureFreezesPendingCallsAndLateScopesCannotChangeARetriedSnapshot() {
        final var ledger = new AiCaptureAccounting(); final var pending = ledger.enter();
        final var snapshot = ledger.closeAndSnapshot();
        pending.captured(true); pending.failed(); pending.exit();
        Assert.assertNull(ledger.enter());
        Assert.assertEquals(ledger.closeAndSnapshot(), snapshot);
        Assert.assertEquals(((Map<?, ?>) snapshot.get("counts")).get("entered"), 1L);
        Assert.assertEquals(((Map<?, ?>) snapshot.get("counts")).get("captured"), 0L);
        Assert.assertEquals(((Map<?, ?>) snapshot.get("counts")).get("finished"), 0L);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void saturatingCountersStayNonnegativeAndCannotSupplyAnExactDenominator() throws Exception {
        final var ledger = new AiCaptureAccounting();
        final var field = AiCaptureAccounting.class.getDeclaredField("counts"); field.setAccessible(true);
        ((long[]) field.get(ledger))[0] = Long.MAX_VALUE;
        final var scope = ledger.enter(); scope.unsupported(); scope.exit(); scope.exit();
        final var snapshot = ledger.closeAndSnapshot();
        Assert.assertEquals(snapshot.get("overflowed"), true);
        final Map<?, ?> counts = (Map<?, ?>) snapshot.get("counts");
        Assert.assertEquals(counts.get("entered"), Long.MAX_VALUE);
        Assert.assertEquals(counts.get("finished"), 1L);
    }
}
