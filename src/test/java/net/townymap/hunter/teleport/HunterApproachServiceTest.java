package net.townymap.hunter.teleport;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class HunterApproachServiceTest {
    @Test void oneFailedHunterLookupIsIsolatedFromOtherActors(){var failed=new CompletableFuture<String>();failed.completeExceptionally(new RuntimeException("API failure"));var good=CompletableFuture.completedFuture("ok");assertNull(HunterApproachService.isolate(failed).join());assertEquals("ok",HunterApproachService.isolate(good).join());}
}
