package com.elfmcys.ysm.natives.legacy;

import java.util.Arrays;

/** Host validation vocabulary for portable legacy payloads. */
final class LegacyPayloadContract {
    record PayloadRecord(PayloadKind kind, PayloadEncoding encoding,
                         int logicalId, String name, int meta0, int meta1,
                         int meta2, int payloadSize) {
    }

    enum PayloadKind {
        MANIFEST(1),
        STRING_DATA(2),
        MODEL_DATA(3),
        BLOB_IMAGE(4),
        NAMED_IMAGE(5),
        SOUND_STREAM(6);

        private final int code;

        PayloadKind(int code) {
            this.code = code;
        }

        static PayloadKind fromCode(int code) {
            return Arrays.stream(values())
                    .filter(value -> value.code == code)
                    .findFirst()
                    .orElse(null);
        }
    }

    enum PayloadEncoding {
        DIRECT(0),
        RGBA(1),
        PNG(2),
        JPEG(3),
        WEBP(4),
        AVIF(5),
        OGG_VORBIS(6),
        OGG_OPUS(7),
        ZTX(8);

        private final int code;

        PayloadEncoding(int code) {
            this.code = code;
        }

        boolean isImage() {
            return this == PNG || this == JPEG || this == WEBP
                    || this == AVIF || this == ZTX;
        }

        static PayloadEncoding fromCode(int code) {
            return Arrays.stream(values())
                    .filter(value -> value.code == code)
                    .findFirst()
                    .orElse(null);
        }
    }

    static final class ProtocolException extends IllegalStateException {
        ProtocolException(String message) {
            super(message);
        }

        ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }

        NativeLegacyStatus status() {
            return NativeLegacyStatus.RESULT_PROTOCOL;
        }
    }
}
