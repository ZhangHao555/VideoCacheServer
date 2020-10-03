package com.ahao.videocacheserver.cache;

import com.ahao.videocacheserver.util.CloseUtil;

import java.io.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FilesDataStream extends InputStream {
    private static final Logger logger = Logger.getLogger("FilesDataStream");

    private ListFile files;
    private int totalLength;
    private int pos;

    private InputStream bis;

    private int startOffset;

    private boolean curIsFirstFile = true;

    public FilesDataStream(ListFile files, int totalLength) {
        this(files, 0, totalLength);
    }

    public FilesDataStream(ListFile files, int startOffset, int totalLength) {
        this.totalLength = totalLength;
        pos = 0;
        this.startOffset = startOffset;
        this.files = files;
    }


    @Override
    public int read() throws IOException {
        if (pos >= totalLength) {
            return -1;
        }

        if (curIsFirstFile) {
            File f = files.consume();
            bis = new BufferedInputStream(new FileInputStream(f));
            bis.skip(startOffset);
            curIsFirstFile = false;
            logger.log(Level.INFO, "skip " + startOffset + " bytes");
        }
        if (bis == null) {
            return -1;
        }
        int read = -1;
        try {
            read = bis.read();
            if (read == -1) {
                bis.close();
                File consumeFile = files.consume();
                if (consumeFile == null) {
                    bis = null;
                    return -1;
                }
                bis = new BufferedInputStream(new FileInputStream(consumeFile));
                read = bis.read();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        pos++;
        return read;
    }

    @Override
    public void close() throws IOException {
        super.close();
        CloseUtil.close(bis);
    }

    public int getTotalLength() {
        return totalLength;
    }
}
