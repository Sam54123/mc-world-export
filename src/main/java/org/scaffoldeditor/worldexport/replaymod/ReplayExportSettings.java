package org.scaffoldeditor.worldexport.replaymod;

public final class ReplayExportSettings {
    private int viewDistance = 8;
    private int lowerDepth = 0;

    public int getViewDistance() {
        return viewDistance;
    }

    public ReplayExportSettings setViewDistance(int viewDistance) {
        if (viewDistance < 1) {
            throw new IllegalArgumentException("Minimum view distance is 1.");
        }

        this.viewDistance = viewDistance;
        return this;
    }
    
    /**
     * Get the lower depth.
     * @return Lower depth in section coordinates.
     */
    public int getLowerDepth() {
        return lowerDepth;
    }

    /**
     * Set the lower depth.
     * @param lowerDepth Lower depth in section coordinates.
     * @return <code>this</code>
     */
    public ReplayExportSettings setLowerDepth(int lowerDepth) {
        this.lowerDepth = lowerDepth;
        return this;
    }
}
