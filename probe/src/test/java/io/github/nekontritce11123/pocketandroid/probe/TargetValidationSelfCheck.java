package io.github.nekontritce11123.pocketandroid.probe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** Plain JVM self-check; no Android, network, test framework or credentials. */
public final class TargetValidationSelfCheck {
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Validation self-check failed");
    }

    private static void rejected(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Invalid target accepted");
        } catch (IllegalArgumentException expected) {
            check(expected.getCause() == null);
            check(!expected.getMessage().contains("secret"));
        }
    }

    public static void main(String[] args) throws Exception {
        check(TargetValidation.url("https://api.ipify.org").getProtocol().equals("https"));
        check(TargetValidation.url("http://192.168.0.2:55887/marker").getPort() == 55887);
        check(TargetValidation.url("http://[2001:db8::1]:8080/").getPort() == 8080);
        check(TargetValidation.host("example.com").equals("example.com"));
        check(TargetValidation.host("[2001:db8::1]").equals("2001:db8::1"));
        check(TargetValidation.host("пример.рф").equals("xn--e1afmkfd.xn--p1ai"));
        check(TargetValidation.port(65535) == 65535);
        check(TargetValidation.interval(2) == 2);
        for (String text : new String[] {null, "", "ftp://secret.example", "file:///secret",
                "http://user:secret@example.com/", "https://example.com/#secret", "http://example.com:0/",
                "https://example.com:65536/", "http://example.com:bad/", "http://example.com/ bad"}) {
            rejected(() -> TargetValidation.url(text));
        }
        for (String text : new String[] {null, "", "[]", ".", "secret/path", "user@secret", "a..secret", "fe80::1%eth0"}) {
            rejected(() -> TargetValidation.host(text));
        }
        rejected(() -> TargetValidation.port(0));
        rejected(() -> TargetValidation.port(65536));
        rejected(() -> TargetValidation.interval(0));
        rejected(() -> TargetValidation.interval(61));
        Path directory = Files.createTempDirectory(Paths.get(args[0]), "probe-target-");
        Path file = directory.resolve("test-target.properties");
        try {
            check(TargetValidation.readTarget(file.toFile()).isEmpty());
            Files.write(file, ("url=http://192.168.0.2:55887/pocket-test/uid1110372\n"
                    + "resolveHost=example.com\ninterval=2\nudpHost=192.168.0.2\nudpPort=55887\n")
                    .getBytes(StandardCharsets.US_ASCII));
            Properties target = TargetValidation.readTarget(file.toFile());
            check(target.getProperty("url", "").endsWith("/pocket-test/uid1110372"));
            check(target.getProperty("udpPort", "").equals("55887"));
            Files.write(file, "unknown=secret\n".getBytes(StandardCharsets.US_ASCII));
            rejected(() -> TargetValidation.readTarget(file.toFile()));
            Files.write(file, new byte[4097]);
            rejected(() -> TargetValidation.readTarget(file.toFile()));
            Files.write(file, "udpHost=192.168.0.2\n".getBytes(StandardCharsets.US_ASCII));
            rejected(() -> TargetValidation.readTarget(file.toFile()));
            Files.write(file, "interval=0\n".getBytes(StandardCharsets.US_ASCII));
            rejected(() -> TargetValidation.readTarget(file.toFile()));
        } finally {
            Files.deleteIfExists(file);
            Files.delete(directory);
        }
        System.out.println("PASS: URL, DNS/UDP host, port and interval validation; no network requests");
        System.out.println("PASS: private target file, missing defaults, size limit and invalid configuration");
    }
}
