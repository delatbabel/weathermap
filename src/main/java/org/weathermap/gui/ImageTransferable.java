package org.weathermap.gui;

import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * One image, offered to the clipboard as an image.
 *
 * <p>The image rather than the file it was written to. A chart is usually wanted
 * in a message or a document, and handing over a path into
 * {@code ~/.weathermap/maps} makes whoever receives it do the fetching.</p>
 *
 * <p>A class of its own rather than an anonymous one inside the window, because
 * the {@link Transferable} contract is the kind of thing that is easy to get
 * subtly wrong - returning data for a flavour that was not offered, or throwing
 * the wrong exception - and none of that is reachable from a running
 * application.</p>
 *
 * @param image what the clipboard will hold
 */
public record ImageTransferable(Image image) implements Transferable {

    public ImageTransferable {
        if (image == null) throw new IllegalArgumentException("no image to copy");
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        // A fresh array each time: the caller is free to modify what it is
        // given, and a shared one would let it edit ours.
        return new DataFlavor[]{DataFlavor.imageFlavor};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return DataFlavor.imageFlavor.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
        return image;
    }
}
