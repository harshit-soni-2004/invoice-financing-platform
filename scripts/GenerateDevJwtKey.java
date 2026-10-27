import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * DEVELOPMENT ONLY. Writes a new RSA-3072 signing key as PKCS#8 PEM for auth-service.
 * Usage: java scripts/GenerateDevJwtKey.java [output-path]   (default: infra/keys/jwt-signing-key.pem)
 * Refuses to overwrite an existing key. The key material is never printed.
 */
public class GenerateDevJwtKey {

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        Path out = Path.of(args.length > 0 ? args[0] : "infra/keys/jwt-signing-key.pem").toAbsolutePath();
        if (Files.exists(out)) {
            System.out.println("Key already exists, leaving it unchanged: " + out);
            return;
        }
        Files.createDirectories(out.getParent());

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair pair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        try {
            if (out.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(out, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(out);
            }
        } catch (FileAlreadyExistsException e) {
            System.out.println("Key already exists, leaving it unchanged: " + out);
            return;
        }
        Files.writeString(out, pem, StandardCharsets.US_ASCII, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Wrote development RSA-3072 signing key: " + out);
    }
}
