/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.common.crypto;

import bisq.common.UserThread;
import bisq.common.util.Utilities;

import com.google.protobuf.ByteString;

import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.wallet.Protos;

import org.bouncycastle.crypto.params.KeyParameter;

import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;

import java.util.Base64;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CryptoUtils {
    public static String pubKeyToString(PublicKey publicKey) {
        final X509EncodedKeySpec x509EncodedKeySpec = new X509EncodedKeySpec(publicKey.getEncoded());
        return Base64.getEncoder().encodeToString(x509EncodedKeySpec.getEncoded());
    }

    public static byte[] getRandomBytes(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /**
     * Copied from core ScryptUtil, should unify the code.
     * @return
     */
    public static KeyCrypterScrypt getKeyCrypterScrypt(byte[] salt) {
        Protos.ScryptParameters scryptParameters = Protos.ScryptParameters.newBuilder()
                .setP(6)
                .setR(8)
                .setN(32768)
                .setSalt(ByteString.copyFrom(salt))
                .build();
        return new KeyCrypterScrypt(scryptParameters);
    }

    /**
     * Copied from core ScryptUtil, should unify the code.
     * @param keyCrypterScrypt
     * @param password
     * @return
     */
    public static KeyParameter deriveKeyWithScrypt(KeyCrypterScrypt keyCrypterScrypt, String password) {
        try {
            log.debug("Doing key derivation");
            long start = System.currentTimeMillis();
            KeyParameter aesKey = keyCrypterScrypt.deriveKey(password);
            long duration = System.currentTimeMillis() - start;
            log.debug("Key derivation took {} msec", duration);
            return aesKey;
        } catch (Throwable t) {
            t.printStackTrace();
            log.error("Key derivation failed. " + t.getMessage());
            throw t;
        }
    }
}
