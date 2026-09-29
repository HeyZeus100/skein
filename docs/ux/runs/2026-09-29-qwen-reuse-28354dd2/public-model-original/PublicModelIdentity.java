import app.skein.core.model.Blake3;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class PublicModelIdentity {
    private static final long EXPECTED_BYTES = 1590475744L;
    private static final String EXPECTED_SHA256 =
        "2c5f9a121ae6695208e300c16acca303669afa4e18812061164dca9c97071b12";

    public static void main(String[] args) throws Exception {
        Path model = Path.of(args[0]);
        if (Files.size(model) != EXPECTED_BYTES) throw new IllegalStateException("Unexpected model size");
        if (!HexFormat.of().formatHex(new Blake3.Hasher().digest()).equals(
                "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262")) {
            throw new IllegalStateException("Existing BLAKE3 class empty vector mismatch");
        }
        Blake3.Hasher blake3 = new Blake3.Hasher();
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        long total = 0, nextProgress = 128L * 1024 * 1024;
        long started = System.nanoTime();
        try (InputStream input = Files.newInputStream(model)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                blake3.update(buffer, 0, count);
                sha256.update(buffer, 0, count);
                total += count;
                if (total >= nextProgress) {
                    System.out.println("{\"event\":\"progress\",\"bytes\":" + total
                        + ",\"elapsed_ms\":" + ((System.nanoTime() - started) / 1000000) + "}");
                    nextProgress += 128L * 1024 * 1024;
                }
            }
        }
        String sha = HexFormat.of().formatHex(sha256.digest());
        String b3 = HexFormat.of().formatHex(blake3.digest());
        if (total != EXPECTED_BYTES || !sha.equals(EXPECTED_SHA256)) {
            throw new IllegalStateException("Full model identity did not match pinned size/SHA-256");
        }
        System.out.println("{\"event\":\"complete\",\"bytes\":" + total
            + ",\"sha256\":\"" + sha + "\",\"blake3\":\"" + b3
            + "\",\"elapsed_ms\":" + ((System.nanoTime() - started) / 1000000)
            + ",\"classification\":\"host public-model identity; not device benchmark\"}");
    }
}
