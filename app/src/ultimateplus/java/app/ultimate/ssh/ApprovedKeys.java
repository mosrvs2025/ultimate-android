package app.ultimate.ssh;

import java.security.PublicKey;
import java.util.*;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;

/** Parses at the approval boundary. Malformed, option-bearing and unsupported keys fail closed. */
public final class ApprovedKeys {
    private volatile List<PublicKey> keys = Collections.emptyList();
    public static List<PublicKey> parse(String text) throws Exception {
        if (text.length() > 65536) throw new IllegalArgumentException("Key list is too large");
        List<PublicKey> result = new ArrayList<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (!(line.startsWith("ssh-ed25519 ") || line.startsWith("ssh-rsa ") || line.startsWith("ecdsa-sha2-nistp256 ")))
                throw new IllegalArgumentException("Use an OpenSSH Ed25519, RSA or P-256 public key without options");
            AuthorizedKeyEntry entry = AuthorizedKeyEntry.parseAuthorizedKeyEntry(line);
            if (entry == null) throw new IllegalArgumentException("Invalid public key");
            PublicKey key = entry.resolvePublicKey(null, PublicKeyEntryResolver.FAILING);
            if (key == null) throw new IllegalArgumentException("Unsupported public key");
            if ("RSA".equals(key.getAlgorithm()) && KeyUtils.getKeySize(key) < 2048)
                throw new IllegalArgumentException("RSA keys must be at least 2048 bits");
            if (result.stream().noneMatch(k -> KeyUtils.compareKeys(k, key))) result.add(key);
            if (result.size() > 32) throw new IllegalArgumentException("Approve at most 32 keys");
        }
        return Collections.unmodifiableList(result);
    }
    public void replace(String text) throws Exception { keys = parse(text); }
    public boolean accepts(String username, PublicKey key) {
        return "ultimate".equals(username) && keys.stream().anyMatch(k -> KeyUtils.compareKeys(k, key));
    }
    public boolean isEmpty() { return keys.isEmpty(); }
    public List<String> fingerprints() {
        List<String> result = new ArrayList<>();
        for (PublicKey key : keys) result.add(KeyUtils.getFingerPrint(key));
        return result;
    }
}
