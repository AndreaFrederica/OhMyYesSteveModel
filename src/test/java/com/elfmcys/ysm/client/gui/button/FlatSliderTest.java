package com.elfmcys.ysm.client.gui.button;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlatSliderTest {
    @Test
    void displayedValueDoesNotInvokeMolangWriteback() {
        var slider = new FlatSlider(0, 0, "", 0,
                null, "v.mode", 1, 0, 2);

        slider.setDisplayedValue(2);

        assertEquals(2, slider.getValue());
    }
}
