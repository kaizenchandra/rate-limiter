import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;

/**
 * LOCAL DEVELOPMENT ONLY: ephemeral signing keys and public test tokens.
 */
class DevIssuer {
    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    static String b(byte[] a) {
        return B64.encodeToString(a);
    }

    static byte[] unsigned(java.math.BigInteger n) {
        var a = n.toByteArray();
        return a[0] == 0 ? Arrays.copyOfRange(a, 1, a.length) : a;
    }

    public static void main(String[] args) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var pub = (RSAPublicKey) pair.getPublic();
        var server = HttpServer.create(new InetSocketAddress(9000), 16);
        String jwks =
                "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"dev\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
                        + b(unsigned(pub.getModulus()))
                        + "\",\"e\":\""
                        + b(unsigned(pub.getPublicExponent()))
                        + "\"}]}";
        server.createContext(
                "/jwks",
                ex -> {
                    byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().write(body);
                    ex.close();
                });
        server.createContext(
                "/tokens",
                ex -> {
                    try {
                        String subject = "demo-user";
                        String query = ex.getRequestURI().getRawQuery();
                        if (query != null) {
                            if (!query.matches("subject=[a-zA-Z0-9_-]{1,64}")) {
                                ex.sendResponseHeaders(400, -1);
                                ex.close();
                                return;
                            }
                            subject = query.substring("subject=".length());
                        }
                        long now = System.currentTimeMillis() / 1000;
                        String header =
                                b("{\"alg\":\"RS256\",\"kid\":\"dev\"}".getBytes(StandardCharsets.UTF_8));
                        String payload =
                                b(
                                        ("{\"iss\":\"http://localhost:9000\",\"aud\":\"rate-limiter\",\"sub\":\""
                                                + subject
                                                + "\",\"tenant_id\":\"demo\",\"api_key_id\":\"demo-key\",\"tier\":\"PREMIUM\",\"scope\":\"limiter.admin"
                                                + " limiter.evaluate metrics\",\"iat\":"
                                                + now
                                                + ",\"exp\":"
                                                + (now + 3600)
                                                + "}")
                                                .getBytes(StandardCharsets.UTF_8));
                        String signing = header + "." + payload;
                        var sig = Signature.getInstance("SHA256withRSA");
                        sig.initSign(pair.getPrivate());
                        sig.update(signing.getBytes(StandardCharsets.UTF_8));
                        byte[] body =
                                ("{\"access_token\":\"" + signing + "." + b(sig.sign()) + "\"}")
                                        .getBytes(StandardCharsets.UTF_8);
                        ex.getResponseHeaders().add("Content-Type", "application/json");
                        ex.sendResponseHeaders(200, body.length);
                        ex.getResponseBody().write(body);
                        ex.close();
                    } catch (Exception e) {
                        ex.sendResponseHeaders(500, -1);
                        ex.close();
                    }
                });
        server.start();
        System.out.println(
                "Development issuer listening on :9000; /tokens creates privileged DEMO tokens. Never"
                        + " expose publicly.");
    }
}
