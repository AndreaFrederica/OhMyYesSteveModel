package com.elfmcys.ysm.client.sound.codec;

import com.elfmcys.ysm.client.sound.stream.AudioStreamProvider;
import org.jetbrains.annotations.Nullable;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

// 把 provider 挂在 URL 自己的 URLStreamHandler 上，由 YsmAudioCodec 在 initialize 时取回。
// 不设全局注册表：provider 的存活期等于 paulscode 持有该 URL 的时间，交给 GC
public final class YsmAudioUrl {
    // paulscode 是按 identifier 的扩展名挑 codec 的（FilenameURL.getFilename()），不是按 URL 路径
    public static final String EXTENSION = "ysmaudio";

    private static final String PROTOCOL = "ysmaudio";

    private YsmAudioUrl() {
    }

    public static URL create(AudioStreamProvider provider) throws MalformedURLException {
        return URL.of(URI.create(PROTOCOL + ":stream"), new Handler(provider));
    }

    public static String identifier() {
        return PROTOCOL + ":stream." + EXTENSION;
    }

    @Nullable
    public static AudioStreamProvider providerOf(URL url) {
        try {
            if (url.openConnection() instanceof Connection connection) {
                return connection.provider;
            }
        } catch (Exception ignored) {
            // 不是本模组构造的 URL
        }
        return null;
    }

    private static final class Handler extends URLStreamHandler {
        private final AudioStreamProvider provider;

        private Handler(AudioStreamProvider provider) {
            this.provider = provider;
        }

        @Override
        protected URLConnection openConnection(URL url) {
            return new Connection(url, provider);
        }
    }

    private static final class Connection extends URLConnection {
        private final AudioStreamProvider provider;

        private Connection(URL url, AudioStreamProvider provider) {
            super(url);
            this.provider = provider;
        }

        @Override
        public void connect() {
        }
    }
}
