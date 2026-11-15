package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReaderNumericSliderTest {
    @Test public void textAndEpubShowExactUnits() {
        assertEquals("12pt", ReaderNumericSlider.points(12));
        assertEquals("38pt", ReaderNumericSlider.points(38));
        assertEquals("9dp", ReaderNumericSlider.dp(9));
        assertEquals("50dp", ReaderNumericSlider.dp(50));
        assertEquals("1.00배", ReaderNumericSlider.spacing(100));
        assertEquals("1.25배", ReaderNumericSlider.spacing(125));
        assertEquals("2.40배", ReaderNumericSlider.spacing(240));
    }

    @Test public void comicsShowCurrentContrastAndSignedBrightness() {
        assertEquals("50%", ReaderNumericSlider.percent(50));
        assertEquals("100%", ReaderNumericSlider.percent(100));
        assertEquals("200%", ReaderNumericSlider.percent(200));
        assertEquals("-100", ReaderNumericSlider.brightness(-100));
        assertEquals("0", ReaderNumericSlider.brightness(0));
        assertEquals("+35", ReaderNumericSlider.brightness(35));
    }
}
