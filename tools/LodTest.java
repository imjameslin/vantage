import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.chunk.LodSelfTest;
import net.minecraft.server.Bootstrap;

/**
 * Stage 3 (docs/stage3/LOD-DESIGN.md §8): headless checks of the LOD data layer, store and level formula
 * ({@code net.minecraft.client.renderer.chunk.LodSelfTest} in the patched client jar). No game, no GPU. Exit 1 on failure.
 */
public class LodTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path tmp = Files.createTempDirectory("lodtest");
        int failures = LodSelfTest.run(tmp);
        System.out.println(failures == 0 ? "PASS" : "FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
