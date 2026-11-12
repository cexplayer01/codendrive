package com.james.codendrive;

/**
 * Pure classification logic for clipboard contents and editor image
 * support (v3.4.2). Dependency-free (arrays in, values out) so it is
 * host-testable — see tests/SendPasteLogicTests.java. The service feeds
 * it facts read from the live ClipData/ClipDescription/EditorInfo; all
 * framework calls stay in the service.
 */
public final class ClipScan {

    private ClipScan() { }

    /** True when any declared mime is an image type ("image/png", "image/*", ...). */
    public static boolean descHasImage(String[] descMimes) {
        if (descMimes == null) return false;
        for (String m : descMimes) {
            if (m != null && m.startsWith("image/")) return true;
        }
        return false;
    }

    /**
     * Index of the clip item to treat as the image, or -1. An item
     * qualifies when it carries a Uri AND either the clip description
     * advertises an image type, or the item carries no text of its own
     * (a standalone Uri clip — the pre-3.4.2 fallback for descriptions
     * that don't declare the image). ALL items are scanned: browsers
     * and galleries routinely put the image in item 1 behind a text
     * item, which the old item-0-only read never saw.
     */
    public static int findImageItem(boolean[] itemHasUri, boolean[] itemHasText,
                                    boolean descIsImage) {
        if (itemHasUri == null || itemHasText == null) return -1;
        int n = Math.min(itemHasUri.length, itemHasText.length);
        for (int i = 0; i < n; i++) {
            if (itemHasUri[i] && (descIsImage || !itemHasText[i])) return i;
        }
        return -1;
    }

    /** Index of the first item carrying text, or -1. */
    public static int findTextItem(boolean[] itemHasText) {
        if (itemHasText == null) return -1;
        for (int i = 0; i < itemHasText.length; i++) {
            if (itemHasText[i]) return i;
        }
        return -1;
    }

    /**
     * True when the editor's declared content mime types accept images:
     * an explicit image type, "image/*", or the "*\/*" accept-anything
     * wildcard. A null array means the editor declared nothing — the
     * caller treats that as "unknown" (distinct from false here only
     * when the array itself is non-null but image-free).
     */
    public static boolean editorAcceptsImage(String[] contentMimeTypes) {
        if (contentMimeTypes == null) return false;
        for (String m : contentMimeTypes) {
            if (m == null) continue;
            if (m.equals("*/*") || m.equals("image/*") || m.startsWith("image/")) {
                return true;
            }
        }
        return false;
    }
}
