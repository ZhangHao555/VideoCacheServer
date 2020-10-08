package com.ahao.videocacheserver.cache;

import java.io.File;
import java.util.Vector;

public class BlockListFile implements ListFile {

    private Vector<File> files = new Vector<>();
    private volatile boolean isDestroy = false;
    private int totalLength = 0;

    public synchronized File consume() {
        if (files.isEmpty() && isDestroy) {
            return null;
        }
        while (files.isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return files.remove(0);
    }

    public synchronized void server(File file) {
        files.add(file);
        notify();
    }

    public synchronized void destroy() {
        isDestroy = true;
    }

    public int getTotalLength() {
        return totalLength;
    }

    public void setTotalLength(int totalLength) {
        this.totalLength = totalLength;
    }
}
