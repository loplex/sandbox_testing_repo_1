import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Copies one file several times in one JVM: by FileChannel.transferTo, FileInputStream.transferTo and Files.copy,
 * in the order the first argument names (c = channel, s = stream, f = Files.copy), each copy printed with its result.
 */
public class Repro {
    public static void main(String[] args) throws Exception {
        String order = args.length > 0 ? args[0] : "ccc";
        Path dir = Path.of(args.length > 1 ? args[1] : ".");
        Path source = dir.resolve("source.bin");
        byte[] bytes = new byte[1 << 20];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        Files.write(source, bytes);
        System.out.println("java " + System.getProperty("java.version") + ", " + System.getProperty("os.name"));
        for (int i = 0; i < order.length(); i++) {
            char how = order.charAt(i);
            Path target = dir.resolve("copy" + i + ".bin");
            Files.deleteIfExists(target);
            try {
                long copied = switch (how) {
                    case 'c' -> {
                        try (FileChannel in = FileChannel.open(source);
                             FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
                                 StandardOpenOption.WRITE)) {
                            yield in.transferTo(0, in.size(), out);
                        }
                    }
                    case 's' -> {
                        try (FileInputStream in = new FileInputStream(source.toFile());
                             FileOutputStream out = new FileOutputStream(target.toFile())) {
                            yield in.transferTo(out);
                        }
                    }
                    case 'f' -> {
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                        yield Files.size(target);
                    }
                    default -> throw new IllegalArgumentException("unknown: " + how);
                };
                boolean same = Files.mismatch(source, target) == -1;
                System.out.println(i + " " + how + ": ok, " + copied + " bytes, same: " + same);
            } catch (Exception e) {
                System.out.println(i + " " + how + ": " + e);
            }
        }
    }
}
