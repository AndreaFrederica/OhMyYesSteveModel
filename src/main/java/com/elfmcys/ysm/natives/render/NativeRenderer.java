package com.elfmcys.ysm.natives.render;

import com.elfmcys.ysm.accessor.VertexBufferAccessor;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.annotation.Aligned;
import com.elfmcys.ysm.buffer.annotation.Borrowed;
import com.elfmcys.ysm.client.compat.IrisCompat;
import com.elfmcys.ysm.config.ClientConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

public class NativeRenderer {
  private static boolean invalidMatrixLogged;
  public static void render(
      VertexConsumer vertexConsumer,
      PoseStack.Pose pose,
      NativeModelState modelState,
      int vertexCount,
      int light,
      int overlay,
      int color,
      RenderContextType contextType) {
    try (var ignored = com.elfmcys.ysm.natives.NativeProfiler.beginRenderer()) {
      renderChecked(
          vertexConsumer, pose, modelState, vertexCount, light, overlay, color, contextType);
    }
  }

  private static void renderChecked(
      VertexConsumer vertexConsumer,
      PoseStack.Pose pose,
      NativeModelState modelState,
      int vertexCount,
      int light,
      int overlay,
      int color,
      RenderContextType contextType) {
    var renderer = cc.sirrus.ysmlib.YsmRuntime.render().renderer();
    var modelMatrix = pose.pose();
    var normalMatrix = pose.normal();
    var view = RenderSystem.getModelViewMatrix();
    var projection = RenderSystem.getProjectionMatrix();
    if ((!modelMatrix.isFinite() || !normalMatrix.isFinite() || !view.isFinite() || !projection.isFinite())
        && !invalidMatrixLogged) {
      invalidMatrixLogged = true;
      var window = Minecraft.getInstance().getWindow();
      com.elfmcys.ysm.YesSteveModel.LOGGER.error(
          "Invalid YSM render matrices: modelFinite={}, normalFinite={}, viewFinite={}, projectionFinite={}, "
              + "model={}, normal={}, view={}, projection={}, window={}x{}, gui={}x{}, camera={}, context={}",
          modelMatrix.isFinite(), normalMatrix.isFinite(), view.isFinite(), projection.isFinite(),
          invalidValues(modelMatrix), invalidValues(normalMatrix), invalidValues(view), invalidValues(projection),
          window.getWidth(), window.getHeight(), window.getGuiScaledWidth(), window.getGuiScaledHeight(),
          Minecraft.getInstance().options.getCameraType(), contextType);
    }
    var parameters =
        new cc.sirrus.ysmlib.render.Renderer.Parameters(
            modelMatrix,
            normalMatrix,
            view,
            projection,
            packColor(color),
            light,
            overlay,
            IrisCompat.getEntityId(),
            contextType == RenderContextType.IRIS_SHADOW);
    if (modelState.runtimeState().vertices() != vertexCount)
      throw new IllegalArgumentException("Vertex count does not match extracted state");
    var vb = probeVertexConsumer(vertexConsumer, vertexCount);
    if (vb.type != VertexFormatType.FALLBACK && vb.region != null) {
      var layout = cc.sirrus.ysmlib.render.Renderer.Layout.valueOf(vb.type.name());
      renderer.renderInto(modelState.runtimeState(), parameters, layout, vb.region.nio());
      vb.accessor.ysm$advance(vertexCount);
    } else {
      var vertices = renderer.render(modelState.runtimeState(), parameters);
      for (var v : vertices) {
        int rgba = v.rgba(), normal = v.normal();
        vertexConsumer.vertex(
            v.x(),
            v.y(),
            v.z(),
            (rgba & 255) / 255f,
            ((rgba >>> 8) & 255) / 255f,
            ((rgba >>> 16) & 255) / 255f,
            (rgba >>> 24) / 255f,
            v.u(),
            v.v(),
            v.overlay(),
            v.light(),
            ((byte) normal) / 127f,
            ((byte) (normal >>> 8)) / 127f,
            ((byte) (normal >>> 16)) / 127f);
      }
    }
  }

  private static String invalidValues(org.joml.Matrix4fc matrix) {
    var values = new float[16];
    matrix.get(values);
    return invalidValues(values);
  }

  private static String invalidValues(org.joml.Matrix3fc matrix) {
    var values = new float[9];
    matrix.get(values);
    return invalidValues(values);
  }

  private static String invalidValues(float[] values) {
    var result = new StringBuilder();
    for (int index = 0; index < values.length; index++) {
      if (!Float.isFinite(values[index])) {
        if (result.length() > 0) result.append(',');
        result.append(index).append('=').append(values[index]);
      }
    }
    return result.length() == 0 ? "finite" : result.toString();
  }

  @SuppressWarnings("resource")
  private static VertexBuffer probeVertexConsumer(VertexConsumer vertexConsumer, int vertexCount) {
    if (vertexConsumer instanceof VertexBufferAccessor accessor && accessor.ysm$ok()) {
      if (!ClientConfig.USE_COMPATIBILITY_RENDERER.get()) {
        var format = accessor.ysm$vertexFormat();
        if (format == DefaultVertexFormat.NEW_ENTITY) {
          return new VertexBuffer(
              accessor, VertexFormatType.VANILLA, accessor.ysm$reserve(vertexCount));
        } else {
          var irisType = IrisCompat.determineVertexFormatType(format);
          if (irisType.isPresent()) {
            return new VertexBuffer(accessor, irisType.get(), accessor.ysm$reserve(vertexCount));
          }
        }
      }
    }
    return new VertexBuffer(null, VertexFormatType.FALLBACK, null);
  }

  @Borrowed
  @Aligned(64)
  public static NativeBuffer getMatBuffer(PoseStack.Pose pose) {
    var buffer = MatBufferHolder.BUFFER;
    var buf = buffer.nio();

    var view = RenderSystem.getModelViewMatrix();
    var proj = RenderSystem.getProjectionMatrix();

    pose.pose().get(buf);
    view.get(64, buf);
    proj.get(128, buf);
    pose.normal().get(192, buf);

    return buffer;
  }

  private static long packFlags(VertexFormatType v, RenderContextType c) {
    return ((long) v.id() << 2) | c.id();
  }

  private static long packLightAndOverlay(int lightUv, int overlayOv) {
    return ((long) lightUv << 32) | overlayOv;
  }

  static int packColor(int argb) {
    return ((argb >>> 16) & 0xFF) | (argb & 0xFF00) | ((argb & 0xFF) << 16) | (argb & 0xFF000000);
  }

  private static final class MatBufferHolder {
    private static final NativeBuffer BUFFER = NativeBuffer.allocate(256, 64);
  }

  private record VertexBuffer(
      @Nullable VertexBufferAccessor accessor,
      VertexFormatType type,
      @Nullable NativeBuffer region) {}
}
