package com.elfmcys.ysm.api.rendering.v0.event;

import cc.sirrus.ysmlib.scene.ScenePackageAssets;
import cc.sirrus.ysmlib.scene.ScenePackagePlayback;
import com.elfmcys.ysm.api.rendering.v0.SceneView;
import com.elfmcys.ysm.api.rendering.v0.TargetKind;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.event.IModBusEvent;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * General-mesh pass on YSM's mod event bus, after queued host geometry has been flushed.
 * Assets and evaluated camera/light/shadow/material channels are immutable and may be retained.
 * The draw callback is borrowed only during event dispatch; it never advances animation or physics.
 * An extension replacing the framebuffer/material pipeline cancels this event and draws its own pass.
 */
@Cancelable
public final class RenderSceneEvent extends Event implements IModBusEvent {
    private final Object target;
    private final TargetKind targetKind;
    private final ScenePackageAssets assets;
    private final ScenePackagePlayback.Frame frame;
    private final RenderContext context;
    private final boolean firstPerson;
    private SceneView view;
    private Consumer<SceneView> materialDraw;

    public RenderSceneEvent(Object target, TargetKind targetKind, ScenePackageAssets assets,
                            ScenePackagePlayback.Frame frame, RenderContext context, boolean firstPerson,
                            SceneView view, Consumer<SceneView> materialDraw) {
        this.target=Objects.requireNonNull(target);this.targetKind=Objects.requireNonNull(targetKind);
        this.assets=Objects.requireNonNull(assets);this.frame=Objects.requireNonNull(frame);
        this.context=Objects.requireNonNull(context);this.firstPerson=firstPerson;
        this.view=Objects.requireNonNull(view);this.materialDraw=Objects.requireNonNull(materialDraw);
    }
    public Object target() { return target; }
    public TargetKind targetKind() { return targetKind; }
    public ScenePackageAssets assets() { return assets; }
    public ScenePackagePlayback.Frame frame() { return frame; }
    public RenderContext context() { return context; }
    public boolean firstPerson() { return firstPerson; }
    public SceneView view() { return view; }
    public void setView(SceneView view) { requireDispatch();this.view=Objects.requireNonNull(view); }
    /** Draw into the extension's current target. The extension owns that target's color-space conversion. */
    public void drawMaterials(SceneView view) { requireDispatch();materialDraw.accept(Objects.requireNonNull(view)); }
    /** Host ends the borrowed callback even when an extension throws. */
    public void endDispatch() { materialDraw=null; }
    private void requireDispatch() {
        if(materialDraw==null) throw new IllegalStateException("Scene draw callback has expired");
    }
}
