package com.ahao.videocacheserver.cache;

import com.ahao.videocacheserver.HttpResponse;
import com.ahao.videocacheserver.ProxyCharset;
import com.ahao.videocacheserver.util.CloseUtil;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DiskLruCache {
    private String cachePath;

    private static final int CACHE_SLICE = 1024 * 1024 * 5; //5MB

    private ExecutorService service = Executors.newFixedThreadPool(1);

    private int maxSize;

    private final static float TRIM_FACTOR = 0.75f;

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock rLock = rwLock.readLock();
    private final Lock wLock = rwLock.writeLock();
    private volatile int curTotalSize;

    public DiskLruCache(String cachePath, int maxSize) {
        this.cachePath = cachePath;
        this.maxSize = maxSize;
    }

    private int getTotalSize(String cachePath) {
        int total = 0;
        List<File> ret = new ArrayList<>();
        getAllFiles(ret, new File(cachePath));
        for (File file : ret) {
            total += file.length();
        }
        return total;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private File getHeaderParentFile(String host) {
        File file = new File(cachePath + "/headers" + "/" + getTransformedString(host));
        if (!file.exists()) {
            file.mkdirs();
        }
        return file;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private File getContentParentFile(String host, String url) {
        File file = new File(cachePath + "/content" + "/" + getTransformedString(host) + "/" + getTransformedString(url));
        if (!file.exists()) {
            file.mkdirs();
        }
        return file;
    }

    private File getContentRootFile() {
        File file = new File(cachePath + "/content");
        if (!file.exists()) {
            file.mkdirs();
        }
        return file;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void cacheHeaders(String host, String url, HttpResponse response) {
        wLock.lock();

        try {
            File file = new File(getHeaderParentFile(host), getTransformedString(url));

            if (file.exists()) {
                file.delete();
            }

            try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
                file.createNewFile();

                String string = response.getHeadText();
                fileOutputStream.write(string.getBytes(ProxyCharset.CUR_CHARSET));
                fileOutputStream.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } finally {
            wLock.unlock();
        }

    }

    public HttpResponse getCacheHeaders(String host, String url) {
        File file = new File(getHeaderParentFile(host), getTransformedString(url));
        if (!file.exists()) {
            return null;
        }

        rLock.lock();
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            return HttpResponse.parse(fileInputStream);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            rLock.unlock();
        }
    }

    public void clearCacheHeaders(String host, String url) {
        wLock.lock();

        File file = new File(getHeaderParentFile(host), getTransformedString(url));
        if (file.exists()) {
            file.delete();
        }

        wLock.unlock();
    }

    public BlockListFile put(SegmentInfo key, InputStream inputStream) {
        wLock.lock();

        curTotalSize = getTotalSize(cachePath);
        checkToTrim(getContentRootFile());

        int pendingCacheLength = 0;
        try {
            checkToCombine(key);
            List<SegmentInfo> keys = new ArrayList<>();
            // slice start from 0
            int diskRangeStartSlice = key.getStartByte() / CACHE_SLICE;
            int diskRangeEndSlice = key.getEndByte() / CACHE_SLICE;

            for (int i = diskRangeStartSlice; i <= diskRangeEndSlice; i++) {
                int sliceStartByte = i * CACHE_SLICE;
                int sliceEndByte = sliceStartByte + CACHE_SLICE - 1;
                SegmentInfo k = new SegmentInfo(key.getHost(),
                        key.getUrl(),
                        Math.max(sliceStartByte, key.getStartByte()),
                        Math.min(key.getEndByte(), sliceEndByte));
                if (curTotalSize + pendingCacheLength > maxSize) {
                    break;
                }
                pendingCacheLength += k.getLength();
                keys.add(k);
            }

            BlockListFile blockList = new BlockListFile();
            blockList.setTotalLength(pendingCacheLength);
            service.submit(() -> {
                for (SegmentInfo segmentKey : keys) {
                    File f = writeToFile(inputStream, segmentKey);
                    blockList.server(f);
                }
                blockList.destroy();
            });
            return blockList;
        } finally {
            wLock.unlock();
        }
    }

    private void checkToTrim(File parentFile) {
        if (curTotalSize > maxSize) {
            removeEmptyFile(parentFile);
            List<File> ret = new LinkedList<>();
            getAllFiles(ret, parentFile);
            ret.sort((o1, o2) -> Long.compare(o2.lastModified(), o1.lastModified()));
            Iterator<File> iterator = ret.iterator();
            while (iterator.hasNext()) {
                File next = iterator.next();
                long length = next.length();
                if (curTotalSize + length > maxSize * TRIM_FACTOR) {
                    if (next.delete()) {
                        curTotalSize -= length;
                    }
                    iterator.remove();
                }
            }

        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void removeEmptyFile(File parentFile) {
        File[] files = parentFile.listFiles();
        if (files != null && files.length != 0) {
            for (File file : files) {
                if (file.isDirectory()) {
                    removeEmptyFile(file);
                } else {
                    if (file.length() == 0) {
                        file.delete();
                    }
                }
            }
        } else {
            parentFile.delete();
        }
    }

    private void getAllFiles(List<File> ret, File file) {
        if (file.exists()) {
            if (file.isDirectory()) {
                File[] files = file.listFiles();
                if (files != null && files.length > 0) {
                    for (File f : files) {
                        getAllFiles(ret, f);
                    }
                }
            } else {
                ret.add(file);
            }
        }
    }

    private void checkToCombine(SegmentInfo segmentKey) {
        File parentPathFile = getContentParentFile(segmentKey.getHost(), segmentKey.getUrl());
        String[] list = parentPathFile.list((File dir, String name) -> {
            String[] split = name.split("_");
            return split.length >= 2;
        });

        int lastFindToCombine = -1;
        if (list != null && list.length > 0) {
            Arrays.sort(list);
            for (int i = 0; i < list.length; i++) {
                String fileName = list[i];
                String[] ranges = fileName.split("_");
                int startRange = Integer.parseInt(ranges[0]);
                int endRange = Integer.parseInt(ranges[1]);

                if (endRange - startRange + 1 != CACHE_SLICE) {
                    if (lastFindToCombine != -1 && lastFindToCombine + 1 == i) {
                        combineFile(list[lastFindToCombine], list[i]);
                        new File(parentPathFile, list[i]).delete();
                        lastFindToCombine = -1;
                    } else {
                        lastFindToCombine = i;
                    }
                }

            }
        }
    }

    private void combineFile(String f1, String f2) {
        File file1 = new File(f1);
        File file2 = new File(f1);

        int startRange = Integer.parseInt(f1.split("_")[0]);
        int endRange = Integer.parseInt(f2.split("_")[1]);

        FileInputStream inputStream = null;
        RandomAccessFile randomFile = null;
        try {
            inputStream = new FileInputStream(file2);
            randomFile = new RandomAccessFile(file1, "rw");

            long fileLength = randomFile.length();
            randomFile.seek(fileLength);

            byte[] buf = new byte[1024 * 1024];
            int length;
            while ((length = inputStream.read(buf, 0, buf.length)) > 0) {
                randomFile.write(buf, 0, length);
            }

            file1.renameTo(new File(file1.getParent(), startRange + "_" + endRange));

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            CloseUtil.close(randomFile);
            CloseUtil.close(inputStream);
        }

    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private File writeToFile(InputStream inputStream, SegmentInfo segmentKey) {
        File file = new File(getContentParentFile(segmentKey.getHost(), segmentKey.getUrl()), segmentKey.getStartByte() + "_" + segmentKey.getEndByte());
        if (file.exists()) {
            file.delete();
        }
        try {
            file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        int totalLength = segmentKey.getEndByte() - segmentKey.getStartByte() + 1;
        try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
            byte[] buf = new byte[1024 * 512];
            int length;
            while ((length = inputStream.read(buf, 0, Math.min(buf.length, totalLength))) > 0) {
                fileOutputStream.write(buf, 0, length);
                totalLength -= length;
            }
            fileOutputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return file;
    }

    public String getCachePath() {
        return cachePath;
    }

    public void setCachePath(String cachePath) {
        this.cachePath = cachePath;
    }

    public List<CacheResult> get(SegmentInfo segmentInfo) {
        rLock.lock();
        try {
            File parentFile = getContentParentFile(segmentInfo.getHost(), segmentInfo.getUrl());
            if(!parentFile.exists()){
                return null;
            }

            String[] localFiles = parentFile.list((dir, name) -> {
                String[] split = name.split("_");
                if (split.length < 2) {
                    return false;
                }
                return new File(dir, name).length() > 0;
            });

            if (localFiles == null || localFiles.length <= 0) {
                return null;
            }

            List<SegmentInfo> keys = new ArrayList<>();
            List<CacheResult> results = new ArrayList<>();

            int curStartIndex = segmentInfo.getStartByte() / CACHE_SLICE * CACHE_SLICE;
            while (curStartIndex < segmentInfo.getEndByte()) {
                SegmentInfo key = new SegmentInfo(segmentInfo.getHost(), segmentInfo.getUrl(), curStartIndex, Math.min(curStartIndex + CACHE_SLICE - 1, segmentInfo.getEndByte()));
                keys.add(key);
                curStartIndex += CACHE_SLICE;
            }

            if (keys.size() == 0) {
                return null;
            }

            for (int i = 0; i < keys.size(); i++) {
                curStartIndex = keys.get(i).getStartByte() / CACHE_SLICE * CACHE_SLICE;
                if (i == 0) {
                    int skip = segmentInfo.getStartByte() - curStartIndex;
                    results.add(new CacheResult(keys.get(i), null, skip, Math.min(curStartIndex + CACHE_SLICE - 1, segmentInfo.getEndByte()) - curStartIndex));
                } else {
                    results.add(new CacheResult(keys.get(i), null, 0, Math.min(curStartIndex + CACHE_SLICE - 1, segmentInfo.getEndByte()) - curStartIndex));
                }
            }

            Map<SegmentInfo, File> localFilesMap = new HashMap<>();
            for (String localFile : localFiles) {
                String[] s = localFile.split("_");
                int startBytes = Integer.parseInt(s[0]);
                int endBytes = Integer.parseInt(s[1]);
                localFilesMap.put(new SegmentInfo(segmentInfo.getHost(), segmentInfo.getUrl(), startBytes, endBytes), new File(parentFile, localFile));
            }

            for (CacheResult cacheResult : results) {
                SegmentInfo key = cacheResult.getKey();
                File f = localFilesMap.get(key);
                updateModifyTime(f);
                cacheResult.setCachedFile(f);
            }
            return results;
        } finally {
            rLock.unlock();
        }

    }

    private void updateModifyTime(File file) {
        try (RandomAccessFile accessFile = new RandomAccessFile(file, "rwd")) {
            if (file.exists()) {
                long now = System.currentTimeMillis();
                boolean modified = file.setLastModified(now);
                if (!modified) {
                    long size = file.length();
                    if (size == 0) {
                        file.delete();
                        return;
                    }

                    accessFile.seek(size - 1);
                    byte lastByte = accessFile.readByte();
                    accessFile.seek(size - 1);
                    accessFile.write(lastByte);
                    accessFile.close();
                }
            }
        } catch (Exception ignore) {

        }

    }

    public static class CacheResult {
        private SegmentInfo key;
        private File cachedFile;
        private int startBytes;
        private int endBytes;

        public CacheResult(SegmentInfo key, File cachedFile, int startBytes, int endBytes) {
            this.key = key;
            this.startBytes = startBytes;
            this.cachedFile = cachedFile;
            this.endBytes = endBytes;
        }

        public boolean isCached() {
            return cachedFile != null && cachedFile.exists() && cachedFile.length() > 0;
        }

        public SegmentInfo getKey() {
            return key;
        }

        public void setKey(SegmentInfo key) {
            this.key = key;
        }

        public File getCachedFile() {
            return cachedFile;
        }

        public void setCachedFile(File cachedFile) {
            this.cachedFile = cachedFile;
        }

        public int getStartBytes() {
            return startBytes;
        }

        public void setStartBytes(int startBytes) {
            this.startBytes = startBytes;
        }

        public int getEndBytes() {
            return endBytes;
        }

        public void setEndBytes(int endBytes) {
            this.endBytes = endBytes;
        }
    }

    private String getTransformedString(String string) {
        return string.replaceAll("[/:.]", "_");
    }

}
