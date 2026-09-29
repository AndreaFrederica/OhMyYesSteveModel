package cc.sirrus.ysmlib.render;

/** Selects one locator subtree without mutating animation output or hiding ancestor transforms. */
public final class LocatorVisibility {
    private LocatorVisibility() {}

    public static float[] select(BakedModel model, float[] attributes, int locator) {
        if (locator < 1 || locator > 255 || attributes.length != model.bones().size() * ModelState.ATTRIBUTE_COUNT)
            throw new IllegalArgumentException("Invalid locator selection");
        float[] selected = attributes.clone();
        int end = 0;
        for (int i = 0; i < model.bones().size(); i++) {
            int offset = i * ModelState.ATTRIBUTE_COUNT;
            if (attributes[offset + 11] == locator) end = Math.max(end, model.bones().get(i).subtreeEnd());
            if (i >= end) selected[offset + 9] = 1;
        }
        return selected;
    }
}
