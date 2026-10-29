package app.ultimate.ssh;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;

/** Stable app-private JCA key format; damaged keys fail startup instead of silently changing identity. */
final class StoredHostKey {
    static synchronized KeyPair load(Path file) throws Exception {
        if (Files.exists(file)) {
            try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
                if (input.readInt() != 0x55535348) throw new IOException("Invalid SSH host key file");
                byte[] publicBytes = read(input), privateBytes = read(input);
                if (input.read() != -1) throw new IOException("Invalid SSH host key file");
                KeyFactory factory = KeyFactory.getInstance("EC");
                KeyPair pair = new KeyPair(factory.generatePublic(new X509EncodedKeySpec(publicBytes)),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes)));
                Signature check = Signature.getInstance("SHA256withECDSA");
                byte[] proof = "Ultimate SSH host identity".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                check.initSign(pair.getPrivate()); check.update(proof); byte[] signature = check.sign();
                check.initVerify(pair.getPublic()); check.update(proof);
                if (!check.verify(signature)) throw new IOException("SSH host key pair does not match");
                return pair;
            }
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        Path temporary = Files.createTempFile(file.getParent(), "hostkey-", ".tmp");
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary.toFile()); DataOutputStream out = new DataOutputStream(stream)) {
                out.writeInt(0x55535348); write(out, pair.getPublic().getEncoded()); write(out, pair.getPrivate().getEncoded());
                out.flush(); stream.getFD().sync();
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
        return pair;
    }
    private static byte[] read(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > 8192) throw new IOException("Invalid SSH host key length");
        byte[] value = new byte[length]; input.readFully(value); return value;
    }
    private static void write(DataOutputStream out, byte[] value) throws IOException { out.writeInt(value.length); out.write(value); }
}
