package hybrid.build;

import java.nio.file.*;

/** Resolves changed classes without booting the game or opening a database connection. */
public final class LinkSmoke {
    public static void main(String[] args) throws Exception {
        int count = 0;
        for (String entry : Files.readAllLines(Path.of(args[0]))) {
            if (!entry.endsWith(".class")) continue;
            var type = Class.forName(entry.substring(0, entry.length() - 6).replace('/', '.'), false,
                    ClassLoader.getSystemClassLoader());
            type.getDeclaredMethods();
            type.getDeclaredConstructors();
            type.getDeclaredFields();
            count++;
        }
        System.out.println("Verified and linked " + count + " changed classes");
    }
}
