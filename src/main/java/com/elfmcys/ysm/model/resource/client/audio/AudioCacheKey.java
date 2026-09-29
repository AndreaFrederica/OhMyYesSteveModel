package com.elfmcys.ysm.model.resource.client.audio;

import cc.sirrus.ysmlib.audio.SupportedAudioProbe;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.SoundSource;

import java.util.Objects;

record AudioCacheKey(ModelFileIdentity representation, int streamId,
                     SupportedAudioProbe.Encoding encoding, int channels,
                     long sampleRate, long frames, Hash256 logicalHash) {
    AudioCacheKey {
        Objects.requireNonNull(representation, "representation");
        Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(logicalHash, "logicalHash");
    }

    static AudioCacheKey from(SoundSource source) {
        var stream = source.stream();
        return new AudioCacheKey(source.representation(), stream.streamId(),
                stream.encoding(), stream.channels(), stream.sampleRate(),
                stream.frames(), stream.locator().logicalHash());
    }
}
