package org.weathermap.gui;

import org.junit.jupiter.api.Test;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageTransferableTest {

    private static final BufferedImage CHART =
            new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);

    @Test
    void itOffersTheImageFlavourAndHandsBackTheImage() throws Exception {
        final ImageTransferable transferable = new ImageTransferable(CHART);

        assertArrayEquals(new DataFlavor[]{DataFlavor.imageFlavor},
                          transferable.getTransferDataFlavors());
        assertTrue(transferable.isDataFlavorSupported(DataFlavor.imageFlavor));
        assertSame(CHART, transferable.getTransferData(DataFlavor.imageFlavor));
    }

    /** Handing back data for a flavour that was never offered is the classic bug. */
    @Test
    void anyOtherFlavourIsRefused() {
        final ImageTransferable transferable = new ImageTransferable(CHART);

        assertFalse(transferable.isDataFlavorSupported(DataFlavor.stringFlavor));
        assertThrows(UnsupportedFlavorException.class,
                     () -> transferable.getTransferData(DataFlavor.stringFlavor));
    }

    /** The flavour array is handed out, so it must not be shared. */
    @Test
    void theFlavourArrayIsNotShared() {
        final ImageTransferable transferable = new ImageTransferable(CHART);
        transferable.getTransferDataFlavors()[0] = DataFlavor.stringFlavor;

        assertArrayEquals(new DataFlavor[]{DataFlavor.imageFlavor},
                          transferable.getTransferDataFlavors());
    }

    @Test
    void thereIsNothingToCopyWithoutAnImage() {
        assertThrows(IllegalArgumentException.class, () -> new ImageTransferable(null));
    }
}
