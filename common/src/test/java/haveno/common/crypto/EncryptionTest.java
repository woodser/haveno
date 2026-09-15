/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.common.crypto;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EncryptionTest {

    @TempDir
    Path keyDir;

    @Test
    public void testPasswordChangeOnlyRewrapsSymmetricKey() throws Exception {
        KeyStorage storage = new KeyStorage(keyDir.toFile());
        KeyRing ring = new KeyRing(storage, null, true);
        byte[] signature = Files.readAllBytes(keyDir.resolve("sig.key"));
        byte[] encryption = Files.readAllBytes(keyDir.resolve("enc.key"));
        byte[] original = Files.readAllBytes(keyDir.resolve("sym.p12"));

        byte[] replacement = storage.preparePasswordChange(null, "new-password");
        assertArrayEquals(original, Files.readAllBytes(keyDir.resolve("sym.p12")));
        storage.commitPasswordChange(replacement);
        assertArrayEquals(signature, Files.readAllBytes(keyDir.resolve("sig.key")));
        assertArrayEquals(encryption, Files.readAllBytes(keyDir.resolve("enc.key")));
        assertEquals(ring.getSymmetricKey(), storage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, "new-password"));
        assertThrows(IncorrectPasswordException.class, () -> storage.loadSecretKey(KeyStorage.KeyEntry.SYM_ENCRYPTION, null));

        storage.commitPasswordChange(storage.preparePasswordChange("new-password", null));
        assertTrue(new KeyRing(storage, null, false).isUnlocked());
    }

    @Test
    public void testInvalidPasswordLeavesKeystoreUntouched() throws Exception {
        KeyStorage storage = new KeyStorage(keyDir.toFile());
        new KeyRing(storage, "old-password", true);
        byte[] original = Files.readAllBytes(keyDir.resolve("sym.p12"));
        assertThrows(IllegalArgumentException.class, () -> storage.preparePasswordChange("old-password", "password-\u00e9"));
        assertThrows(IllegalStateException.class, () -> storage.preparePasswordChange("incorrect", "new-password"));
        assertArrayEquals(original, Files.readAllBytes(keyDir.resolve("sym.p12")));
        assertTrue(new KeyRing(storage, "old-password", false).isUnlocked());
    }

    @Test
    public void testIncompleteAccountsCannotBeOverwritten() throws Exception {
        KeyStorage storage = new KeyStorage(keyDir.toFile());
        new KeyRing(storage, null, true);
        byte[] signature = Files.readAllBytes(keyDir.resolve("sig.key"));
        Files.delete(keyDir.resolve("sym.p12"));
        assertThrows(IllegalStateException.class, () -> new KeyRing(storage, null, true));
        assertArrayEquals(signature, Files.readAllBytes(keyDir.resolve("sig.key")));
    }

    @Test
    public void testRecoveryReplacesOnlySupersededBackupsOfTheSameMasterKey() throws Exception {
        KeyStorage storage = new KeyStorage(keyDir.toFile());
        KeyRing ring = new KeyRing(storage, null, true);
        Path backups = Files.createDirectories(keyDir.resolve("backup/backups_sym_p12"));
        Path old = backups.resolve("old_sym.p12");
        Files.copy(keyDir.resolve("sym.p12"), old);
        Path foreignDir = Files.createDirectory(keyDir.resolve("foreign"));
        KeyStorage foreign = new KeyStorage(foreignDir.toFile());
        new KeyRing(foreign, null, true);
        Path foreignBackup = backups.resolve("password-change_sym.p12");
        Files.copy(foreignDir.resolve("sym.p12"), foreignBackup);
        byte[] foreignBytes = Files.readAllBytes(foreignBackup);
        storage.commitPasswordChange(storage.preparePasswordChange(null, "old-password"));
        storage.commitPasswordChange(storage.preparePasswordChange("old-password", "new-password"));
        storage.finishPasswordChange(ring, "new-password", Arrays.asList("new-password", "old-password"));
        assertEquals(false, Files.exists(old));
        assertArrayEquals(foreignBytes, Files.readAllBytes(foreignBackup));
        assertTrue(Files.exists(backups.resolve("password-change_sym.p12")));
        assertThrows(IncorrectPasswordException.class, () -> storage.verifyPassword(ring.getSymmetricKey(), null));
        storage.verifyPassword(ring.getSymmetricKey(), "new-password");
    }

    // Sizes around AES block (16) and stream chunk (64 KiB) boundaries, plus an empty payload.
    private static final int[] SIZES = {0, 1, 15, 16, 17, 1000, 65_535, 65_536, 65_537, 100_000, 5_000_000};

    @Test
    public void testStreamWriteMatchesArrayAndRoundTrips() throws CryptoException {
        SecretKey key = Encryption.generateSecretKey(256);
        Random random = new Random(1234);
        for (int size : SIZES) {
            byte[] payload = new byte[size];
            random.nextBytes(payload);

            byte[] viaArray = Encryption.encryptPayloadWithHmac(payload, key);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Encryption.encryptPayloadWithHmacToStream(payload, key, bos);
            byte[] viaStream = bos.toByteArray();

            // The streaming variant must be byte-identical so existing persisted files stay readable.
            assertArrayEquals(viaArray, viaStream, "ciphertext differs for payload size " + size);

            // And it must decrypt back to the original payload with the existing array decrypt path.
            byte[] decrypted = Encryption.decryptPayloadWithHmac(viaStream, key);
            assertArrayEquals(payload, decrypted, "round-trip failed for payload size " + size);
        }
    }

    @Test
    public void testWriterVariantMatchesArray() throws CryptoException {
        // The PayloadWriter variant (used with protobuf Message::writeTo to avoid materializing the
        // payload) must be byte-identical to the array variant, including when the payload arrives
        // in many small writes as protobuf's 4 KB CodedOutputStream buffer produces.
        SecretKey key = Encryption.generateSecretKey(256);
        Random random = new Random(4321);
        byte[] payload = new byte[300_000];
        random.nextBytes(payload);

        byte[] viaArray = Encryption.encryptPayloadWithHmac(payload, key);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Encryption.encryptPayloadWithHmacToStream(out -> {
            for (int off = 0; off < payload.length; off += 4096) {
                out.write(payload, off, Math.min(4096, payload.length - off));
            }
        }, key, bos);

        assertArrayEquals(viaArray, bos.toByteArray(), "writer-based ciphertext differs from array variant");
    }

    @Test
    public void testStreamDoesNotCloseOutputStream() throws CryptoException {
        SecretKey key = Encryption.generateSecretKey(256);
        TrackingOutputStream out = new TrackingOutputStream();
        Encryption.encryptPayloadWithHmacToStream(new byte[1000], key, out);
        assertEquals(false, out.closed, "stream must not be closed by the helper");
    }

    private static class TrackingOutputStream extends ByteArrayOutputStream {
        boolean closed = false;

        @Override
        public void close() {
            closed = true;
        }
    }
}
