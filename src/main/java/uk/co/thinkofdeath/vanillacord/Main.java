package uk.co.thinkofdeath.vanillacord;

import com.google.common.io.ByteStreams;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 2) {
            System.out.println("Args: <version> [secret]");
            return;
        }

        String version = args[0];
        String secret = (args.length == 2 && args[1].length() > 0)?args[1]:null;
        boolean secure = secret != null;

        File in = new File("in/" + version + ".jar");
        File out = new File("out/" + version + '-' + ((secure)?"velocity":"bungee") + ".jar");
        out.getParentFile().mkdirs();
        if (out.exists()) out.delete();

        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(in));
             ZipOutputStream zop = new ZipOutputStream(new FileOutputStream(out))) {

            LinkedHashMap<String, byte[]> classes = new LinkedHashMap<>();

            if (secure) System.out.println("Requested modern IP forwarding");
            System.out.println("Loading");

            // So Mojang managed to include the same file
            // multiple times in the server jar. This
            // (not being valid) causes java to (correctly)
            // throw an exception so we need to track what
            // files have been added to a jar so that we
            // don't add them twice
            Set<String> mojangCantEvenJar = new HashSet<>();

            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().endsWith(".class")) {
                    if (mojangCantEvenJar.add(entry.getName())) {
                        zop.putNextEntry(entry);
                        ByteStreams.copy(zip, zop);
                    }
                    continue;
                }
                byte[] clazz = ByteStreams.toByteArray(zip);
                classes.put(entry.getName(), clazz);
            }

            String handshakePacket = null;
            String loginListener = null;
            String loginPacket = null;
            String serverQuery = null;
            String clientQuery = null;
            String networkManager = null;

            for (Map.Entry<String, byte[]> e : new HashMap<>(classes).entrySet()) {
                byte[] clazz = e.getValue();
                ClassReader reader = new ClassReader(clazz);
                TypeChecker typeChecker = new TypeChecker(secure);
                reader.accept(typeChecker, 0);

                if (typeChecker.isHandshakeListener()) {
                    System.out.println("Found the handshake listener in " + e.getKey());

                    reader = new ClassReader(clazz);
                    ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                    HandshakeListener hsl = new HandshakeListener(classWriter, typeChecker, secure);
                    reader.accept(hsl, 0);
                    clazz = classWriter.toByteArray();

                    handshakePacket = hsl.getHandshake();
                    networkManager = hsl.getNetworkManager();
                } else if (typeChecker.isLoginListener()) {
                    System.out.println("Found the login listener in " + e.getKey());
                    loginListener = e.getKey();
                } else if (typeChecker.isServerQuery()) {
                    System.out.println("Found the extended login request in " + e.getKey());
                    serverQuery = e.getKey();
                } else if (typeChecker.isClientQuery()) {
                    System.out.println("Found the extended login response in " + e.getKey());
                    clientQuery = e.getKey();
                }
                classes.put(e.getKey(), clazz);
            }

            // Increase the hostname field size
            if (!secure) {
                byte[] clazz = classes.get(handshakePacket + ".class");
                ClassReader reader = new ClassReader(clazz);
                ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                reader.accept(new HandshakePacket(classWriter), 0);
                clazz = classWriter.toByteArray();
                classes.put(handshakePacket + ".class", clazz);
            }
            // Inject the profile injector and force offline mode
            {
                byte[] clazz = classes.get(loginListener);
                ClassReader reader = new ClassReader(clazz);
                ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                LoginListener ll = new LoginListener(classWriter, networkManager, clientQuery, secret);
                reader.accept(ll, 0);
                clazz = classWriter.toByteArray();
                classes.put(loginListener, clazz);

            // Intercept the login process
                loginPacket = ll.getPacket() + ".class";
                if (secure) {
                    clazz = classes.get(loginPacket);
                    reader = new ClassReader(clazz);
                    classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                    reader.accept(new LoginPacket(classWriter, ll, loginListener), 0);
                    clazz = classWriter.toByteArray();
                    classes.put(loginPacket, clazz);
                }
            }
            // Change the server brand
            {
                byte[] clazz = classes.get("net/minecraft/server/MinecraftServer.class");
                ClassReader classReader = new ClassReader(clazz);
                ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                classReader.accept(new MCBrand(classWriter), 0);
                clazz = classWriter.toByteArray();
                classes.put("net/minecraft/server/MinecraftServer.class", clazz);
            }

            System.out.println("Generating helper classes");

            {
                InputStream clazz = Main.class.getResourceAsStream("/uk/co/thinkofdeath/vanillacord/helper/BungeeHelper.class");
                LinkedHashMap<String, byte[]> queue = new LinkedHashMap<>();
                ClassReader classReader = new ClassReader(clazz);
                ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                classReader.accept(new BungeeHelper(queue, classWriter, networkManager, handshakePacket), 0);
                classes.put("uk/co/thinkofdeath/vanillacord/helper/BungeeHelper.class", classWriter.toByteArray());
                classes.putAll(queue);
                clazz.close();
            }

            if (secure) {
                InputStream clazz = Main.class.getResourceAsStream("/uk/co/thinkofdeath/vanillacord/helper/VelocityHelper.class");
                LinkedHashMap<String, byte[]> queue = new LinkedHashMap<>();
                ClassReader classReader = new ClassReader(clazz);
                ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                classReader.accept(new VelocityHelper(queue, classWriter, networkManager, loginListener, loginPacket, serverQuery, clientQuery), 0);
                classes.put("uk/co/thinkofdeath/vanillacord/helper/VelocityHelper.class", classWriter.toByteArray());
                classes.putAll(queue);
                clazz.close();

            }

            System.out.println("Exporting patched jarfile");
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                zop.putNextEntry(new ZipEntry(e.getKey()));
                zop.write(e.getValue());
            }

            InputStream clazz = Main.class.getResourceAsStream("/uk/co/thinkofdeath/vanillacord/helper/QuietException.class");
            zop.putNextEntry(new ZipEntry("uk/co/thinkofdeath/vanillacord/helper/QuietException.class"));
            ByteStreams.copy(clazz, zop);
            clazz.close();
        }
    }
}
