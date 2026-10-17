package cc.sirrus.ysmlib.render;

/** Selects one locator subtree without mutating animation output or hiding ancestor transforms. */
public final class LocatorVisibility {
    private LocatorVisibility() {}

    public static float[] select(BakedModel model, float[] attributes, int locator) {
        if (locator < 1 || locator > 255 || attributes.length != model.bones().size() * ModelState.ATTRIBUTE_COUNT)
            throw new IllegalArgumentException("Invalid locator selection");
        float[] selected = attributes.clone();
        // A locator can represent several independent roots.  CPM uses this
        // for fpLeft/fpRight and for skeleton.left_arm/right_arm.  Keep the
        // union of every tagged subtree instead of assuming one contiguous
        // range; this preserves each root's original parent transform.
        boolean[] keep = new boolean[model.bones().size()];
        for (int i = 0; i < model.bones().size(); i++) {
            int offset = i * ModelState.ATTRIBUTE_COUNT;
            if (attributes[offset + 11] == locator) {
                int end = Math.min(model.bones().size(), model.bones().get(i).subtreeEnd());
                for (int j = i; j < end; j++) keep[j] = true;
            }
        }
        for (int i = 0; i < model.bones().size(); i++) {
            if (!keep[i]) selected[i * ModelState.ATTRIBUTE_COUNT + 9] = 1;
        }
        return selected;
    }
}
