package ceui.lisa.http;

import android.util.Log;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

/**
 * 不发送 SNI 的 SSLSocketFactory。
 * 用于图片服务器 (i.pximg.net)：GFW 按 SNI 封锁，不发 SNI 则 GFW 看不到域名。
 * 图片服务器不要求 SNI（基于 IP 路由）。
 */
public final class RubySSLSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    public RubySSLSocketFactory() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new TrustAllCertManager()}, null);
            delegate = sslContext.getSocketFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException(e);
        }
    }

    @NotNull
    public Socket createSocket(@Nullable String host, int port) {
        throw new UnsupportedOperationException("Use createSocket(Socket, String, int, boolean)");
    }

    @NotNull
    public Socket createSocket(@Nullable String host, int port, @Nullable InetAddress localAddr, int localPort) {
        throw new UnsupportedOperationException("Use createSocket(Socket, String, int, boolean)");
    }

    @NotNull
    public Socket createSocket(@Nullable InetAddress addr, int port) {
        throw new UnsupportedOperationException("Use createSocket(Socket, String, int, boolean)");
    }

    @NotNull
    public Socket createSocket(@Nullable InetAddress addr, int port, @Nullable InetAddress localAddr, int localPort) {
        throw new UnsupportedOperationException("Use createSocket(Socket, String, int, boolean)");
    }

    @NotNull
    public Socket createSocket(@Nullable Socket socket, @Nullable String host, int port, boolean autoClose) throws IOException {
        if (socket == null) throw new NullPointerException("socket is null");
        String ip = socket.getInetAddress().getHostAddress();
        long start = System.nanoTime();
        Log.d("RubySSL", "──→ No-SNI TLS wrap " + ip + ":" + port);
        // 传 null hostname → Java TLS 不在 ClientHello 中包含 SNI 扩展
        SSLSocket sslSocket = (SSLSocket) delegate.createSocket(socket, null, port, autoClose);
        sslSocket.setEnabledProtocols(sslSocket.getSupportedProtocols());
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        // 不要在这里读 getSession()/getCipherSuite():
        // 1) 此时握手尚未发生(okhttp 之后才 startHandshake),会强制提前握手
        // 2) 若底层 SSL 已被关闭/握手失败,conscrypt 的 ssl 指针为 null,getCipherSuite() 会 NPE 把整个 OkHttp 线程炸掉
        Log.d("RubySSL", "←── TLS socket ready " + ip + ":" + port + " [" + elapsed + "ms]");
        return sslSocket;
    }

    @NotNull
    public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }

    @NotNull
    public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
}
