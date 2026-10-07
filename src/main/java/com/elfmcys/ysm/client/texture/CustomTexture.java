package com.elfmcys.ysm.client.texture;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.util.RenderUtil;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.Optional;

public class CustomTexture extends AbstractTexture {
    private final ImageSource source;
    private final Decoder decoder;
    private final Uploader uploader;
    private @Nullable Throwable failure;

    public CustomTexture(ImageSource source) {
        this(source, CustomTexture::decode, CustomTexture::upload);
    }

    CustomTexture(ImageSource source, Decoder decoder, Uploader uploader) {
        this.source = Objects.requireNonNull(source, "source");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.uploader = Objects.requireNonNull(uploader, "uploader");
    }

    @Override
    public void loadTexture(IResourceManager resourceManager) {
        RenderUtil.assertOnMinecraftThread();
        if (failure != null) {
            return;
        }
        BufferedImage pixels;
        try {
            pixels = decoder.decode(source);
            uploader.upload(this, pixels);
        } catch (Exception error) {
            failure = error;
            YesSteveModel.LOGGER.debug("Failed to load standalone GUI texture from {}", source, error);
        }
    }

    private static BufferedImage decode(ImageSource source) throws Exception {
        try (var image = source.open()) {
            return image.decode();
        }
    }

    private static void upload(CustomTexture texture, BufferedImage img) {
        TextureUtil.uploadTextureImageAllocate(texture.getGlTextureId(), img, false, false);
    }

    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    @FunctionalInterface
    interface Decoder {
        BufferedImage decode(ImageSource source) throws Exception;
    }

    @FunctionalInterface
    interface Uploader {
        void upload(CustomTexture texture, BufferedImage image);
    }
}
