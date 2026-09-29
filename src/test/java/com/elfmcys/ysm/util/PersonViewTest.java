package com.elfmcys.ysm.util;

import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import net.minecraft.client.CameraType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PersonViewTest {
    @Test void externalBodyPassRetainsFirstPersonQueriesWithoutChangingGuiOrOtherPlayers() {
        var body = new RenderContext(false, false, true, false, false, false, false);
        var inventory = new RenderContext(false, false, false, true, false, false, false);
        assertEquals(CameraType.FIRST_PERSON.ordinal(),
                PersonView.getPersonView(true, body, CameraType.FIRST_PERSON));
        assertEquals(CameraType.THIRD_PERSON_BACK.ordinal(),
                PersonView.getPersonView(true, RenderContext.levelImmutable(), CameraType.THIRD_PERSON_BACK));
        assertEquals(CameraType.THIRD_PERSON_FRONT.ordinal(),
                PersonView.getPersonView(true, inventory, CameraType.FIRST_PERSON));
        assertEquals(CameraType.THIRD_PERSON_FRONT.ordinal(),
                PersonView.getPersonView(false, body, CameraType.FIRST_PERSON));
    }
}
