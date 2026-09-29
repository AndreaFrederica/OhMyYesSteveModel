package com.elfmcys.ysm.natives.legacy;

import java.nio.ByteBuffer;

/** Raw JNI transport. Validation and ownership begin in {@link NativeLegacyProtocol}. */
final class NativeLegacyImportResult {
    private final int statusCode;
    private final String diagnostic;
    private final long ownerHandle;
    private final byte[] descriptor;
    private final ByteBuffer[] payloads;

    NativeLegacyImportResult(int statusCode, String diagnostic, long ownerHandle,
                             byte[] descriptor, ByteBuffer[] payloads) {
        this.statusCode = statusCode;
        this.diagnostic = diagnostic;
        this.ownerHandle = ownerHandle;
        this.descriptor = descriptor == null ? null : descriptor.clone();
        this.payloads = payloads == null ? null : payloads.clone();
    }

    int statusCode() {
        return statusCode;
    }

    String diagnostic() {
        return diagnostic;
    }

    long ownerHandle() {
        return ownerHandle;
    }

    byte[] descriptor() {
        return descriptor;
    }

    ByteBuffer[] payloads() {
        return payloads;
    }
}
