package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.Matrix4;

/** Front-face parity for ordinary perspective/orthographic host projections, including GUI Y flips. */
final class SceneWinding {
    private SceneWinding() {}

    static boolean clockwise(Matrix4 modelView, Matrix4 projection) {
        double determinant=modelView.get(0,0)*(double)(modelView.get(1,1)*modelView.get(2,2)-modelView.get(1,2)*modelView.get(2,1))
                -modelView.get(1,0)*(double)(modelView.get(0,1)*modelView.get(2,2)-modelView.get(0,2)*modelView.get(2,1))
                +modelView.get(2,0)*(double)(modelView.get(0,1)*modelView.get(1,2)-modelView.get(0,2)*modelView.get(1,1));
        // Depth mapping (including reversed Z) does not change screen-space winding.
        double screen=projection.get(0,0)*(double)projection.get(1,1)-projection.get(1,0)*(double)projection.get(0,1);
        return (determinant<0) != (screen<0);
    }
}
