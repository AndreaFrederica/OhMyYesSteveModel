package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.archive.ArchiveService;
import cc.sirrus.ysmlib.archive.java.JavaArchiveProvider;
import cc.sirrus.ysmlib.audio.AudioProvider;
import cc.sirrus.ysmlib.audio.java.JavaAudioProvider;
import cc.sirrus.ysmlib.codec.CompressionProvider;
import cc.sirrus.ysmlib.codec.HashProvider;
import cc.sirrus.ysmlib.image.ImageProvider;
import cc.sirrus.ysmlib.image.java.JavaImageProvider;
import java.util.List;
import cc.sirrus.ysmlib.render.natives.NativeRenderProvider;

/** Portable service entry point shared by the Forge prerequisite and headless tools. */
public final class YsmRuntime {
  private static final cc.sirrus.ysmlib.scene.SceneProvider SCENES = new SceneServices();

  /** Portable general mesh imports and CPU animation/deformation, independent of the MC backend. */
  public static cc.sirrus.ysmlib.scene.SceneProvider scenes() { return SCENES; }
  private static final cc.sirrus.ysmlib.legacy.V3EnvelopeProvider V3 =
      new cc.sirrus.ysmlib.legacy.java.JavaV3EnvelopeProvider();

  public static cc.sirrus.ysmlib.legacy.V3EnvelopeProvider v3() {
    return V3;
  }

  private static final cc.sirrus.ysmlib.render.BakeProvider BAKE =
      new cc.sirrus.ysmlib.render.java.JavaBakeProvider();

  public static cc.sirrus.ysmlib.render.BakeProvider bake() {
    return BAKE;
  }

  public static final String MOD_ID = "ysm_runtime";
  private static final boolean JAVA_ONLY = Boolean.getBoolean("ysm.runtime.javaOnly");
  private static final JavaArchiveProvider ARCHIVE_PROVIDER = new JavaArchiveProvider();
  private static final ArchiveService ARCHIVES =
      new ArchiveService(
          ARCHIVE_PROVIDER, List.of(), JAVA_ONLY);
  private static final CodecServices CODECS = CodecServices.configured();
  private static final JavaImageProvider IMAGES = new JavaImageProvider();
  private static final cc.sirrus.ysmlib.legacy.LegacyImportProvider LEGACY =
      new cc.sirrus.ysmlib.legacy.java.JavaLegacyImportProvider(V3, IMAGES);

  public static cc.sirrus.ysmlib.legacy.LegacyImportProvider legacy() {
    return LEGACY;
  }

  private static final cc.sirrus.ysmlib.legacy.DecodedWorkspaceProvider DECODED =
      new cc.sirrus.ysmlib.legacy.java.JavaDecodedWorkspaceProvider();

  public static cc.sirrus.ysmlib.legacy.DecodedWorkspaceProvider decodedWorkspace() {
    return DECODED;
  }

  private static final cc.sirrus.ysmlib.v3d.V3dCache V3D =
      new cc.sirrus.ysmlib.v3d.V3dCache(V3, DECODED);

  public static cc.sirrus.ysmlib.v3d.V3dCache v3d() {
    return V3D;
  }

  private static final cc.sirrus.ysmlib.v3d.V3dCache V3D_WIRE =
      new cc.sirrus.ysmlib.v3d.V3dCache(V3);

  /** Import cache omits human-readable workspace exports. */
  public static cc.sirrus.ysmlib.v3d.V3dCache v3dWire() { return V3D_WIRE; }

  private static final AudioProvider AUDIO = new JavaAudioProvider();

  private static final cc.sirrus.ysmlib.render.RenderProvider RENDER = configuredRender();

  private static cc.sirrus.ysmlib.render.RenderProvider configuredRender() {
    var java = new cc.sirrus.ysmlib.render.java.JavaRenderProvider();
    if (JAVA_ONLY) return java;
    try {
      var library = NativeLibraries.find("render");
      return library == null ? java : new NativeRenderProvider(library);
    } catch (LinkageError | SecurityException | java.nio.file.InvalidPathException unavailable) {
      System.getLogger(YsmRuntime.class.getName()).log(System.Logger.Level.WARNING,
          "Optional renderer accelerator unavailable; using Java", unavailable);
      return java;
    }
  }

  public static cc.sirrus.ysmlib.render.RenderProvider render() {
    return RENDER;
  }

  /** Successful packed native draws; zero for Java and startup self-checks. */
  public static long successfulNativeRenderDraws() {
    return RENDER instanceof NativeRenderProvider provider ? provider.successfulNativeDraws() : 0;
  }

  private YsmRuntime() {}

  public static ArchiveService archives() {
    return ARCHIVES;
  }

  public static HashProvider hashes() {
    return CODECS.hashes;
  }

  public static CompressionProvider compression() {
    return CODECS.compression;
  }

  public static ImageProvider images() {
    return IMAGES;
  }

  public static AudioProvider audio() {
    return AUDIO;
  }

  private static final PhysicsServices PHYSICS = PhysicsServices.configured();
  private static final DeformationServices DEFORMATION = DeformationServices.configured();
  public static cc.sirrus.ysmlib.scene.DeformationProvider deformation() { return DEFORMATION.provider; }

  /** Headless rigid/soft-body solver. Creating the service does not initialize a WASM world. */
  public static cc.sirrus.ysmlib.scene.physics.PhysicsProvider physics() { return PHYSICS.provider; }

  /** Cached startup selection reason; empty when the accelerator was accepted. */
  public static String physicsFallbackReason() { return PHYSICS.fallbackReason; }

  /** Read-only service selections, not a history of which formats have been used. */
  public record ModuleStatus(String module, String implementation) {}

  public static boolean javaOnly() {
    return JAVA_ONLY;
  }

  /** No decoding, file I/O or test calls; codec ids reflect per-capability fallback. */
  public static List<ModuleStatus> diagnostics() {
    return List.of(
        new ModuleStatus("Archives (V1/V2/ZIP/7z)", ARCHIVE_PROVIDER.id()),
        new ModuleStatus("Hash", CODECS.hashes.id()),
        new ModuleStatus("Compression", CODECS.compression.id()),
        new ModuleStatus("Images", IMAGES.id()),
        new ModuleStatus("AVIF", IMAGES.avifDecoderId()),
        new ModuleStatus("Audio", AUDIO.id()),
        new ModuleStatus("Physics", physics().id() + " abi=" + physics().capabilities().abi()
            + " features=0x" + Long.toHexString(physics().capabilities().featureBits())
            + (PHYSICS.fallbackReason.isEmpty() ? "" : " fallback=" + PHYSICS.fallbackReason)),
        new ModuleStatus("Skinning",deformation().id()+(DEFORMATION.fallbackReason.isEmpty()?"":" fallback="+DEFORMATION.fallbackReason)),
        new ModuleStatus("Scene", SCENES.profile()),
        new ModuleStatus("V3 envelope", V3.getClass().getSimpleName()),
        new ModuleStatus("V3 import", LEGACY.getClass().getSimpleName()),
        new ModuleStatus("Decoded", DECODED.getClass().getSimpleName()),
        new ModuleStatus("V3D", V3D.getClass().getSimpleName() + " (JVM)"),
        new ModuleStatus("Bake", BAKE.getClass().getSimpleName()),
        new ModuleStatus("State", RENDER.stateId()),
        new ModuleStatus("Render", RENDER.id()));
  }
}
