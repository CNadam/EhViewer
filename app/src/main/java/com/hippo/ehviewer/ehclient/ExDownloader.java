/*
 * Copyright (C) 2014 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ehclient;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Process;
import android.support.v4.app.NotificationCompat;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.network.HttpHelper;
import com.hippo.ehviewer.util.AutoExpandArray;
import com.hippo.ehviewer.util.BgThread;
import com.hippo.ehviewer.util.Config;
import com.hippo.ehviewer.util.EhUtils;
import com.hippo.ehviewer.util.Log;
import com.hippo.ehviewer.util.Utils;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

// TODO what a mess
public final class ExDownloader implements Runnable {

    private static final String TAG = ExDownloader.class.getSimpleName();

    private static final int IO_BUFFER_SIZE = 8 * 1024;

    private static final String[] URL_509_SUFFIX_ARRAY = {
            "/509.gif",
            "/509s.gif"
    };

    private final Context mContext;
    private final ExDownloaderManager mManager;

    private final int mGid;
    private final String mToken;
    private final String mTitle;
    private final int mMode;
    private volatile int mPreviewPageNum = -1;
    private volatile int mPreviewPerPage = -1;
    private volatile int mImageNum = -1;
    private final AutoExpandArray<String> mPageTokeArray = new AutoExpandArray<String>();
    private final AutoExpandArray<String> mImageFilenameArray = new AutoExpandArray<String>();

    private volatile int mOwnerNum = 0;
    private Thread mMainThread = null;
    /** Only work for EhClient.MODE_LOFI when do not know image number **/
    private int mCurMaxPreviewPage = -1;

    private volatile int mStartIndex = 0;
    private final Queue<Integer> mRequestIndexArray = new ConcurrentLinkedQueue<Integer>();
    private final Queue<Integer> mNoTokenIndexArray = new ConcurrentLinkedQueue<Integer>();
    private final Queue<Integer> mRequestPageIndexArray = new ConcurrentLinkedQueue<Integer>();

    /** The set contains the requsting index **/
    private final Set<Integer> mRequstingIndexSet = new HashSet<Integer>();

    private volatile int mCurRequestPageIndex = -1;

    private final int mWorkerNum;
    private final Worker[] mWorkerArray;
    private final HttpHelper.DownloadControlor[] mControlorArray;

    private final Object mPageTokenLock = new Object();
    private final Object mWorkerLock = new Object();
    private final Object mWorkerGetIndexLock = new Object();

    private volatile boolean mDownloadMode = false;
    private volatile boolean mPauseWork = false;
    private volatile boolean mStopWork = false;

    private final File mDir;
    private ListenerForImageSet mLfis;
    private ListenerForDownload mLfd;

    private static final long UPDATE_SPEED_INTERVAL = 500;
    private volatile long mLastUpdateSpeedTime = 0;
    private volatile long mAccumulateSize;
    private volatile HashSet<Integer> mDownloadIndexSet;
    private final Object mDownloadLock = new Object();

    private int mCurReadIndex = 0;

    /**
     * Do no work in UI thread
     * @author Hippo
     */
    public interface ListenerForImageSet {
        public void onDownloadStart(int index);
        public void onDownloading(int index, float percent);
        public void onDownloadComplete(int index);
        public void onDownloadFail(int index);
    }

    /**
     * Do no work in UI thread
     * @author Hippo
     */
    public interface ListenerForDownload {
        public void onStart(int gid);
        public void onGetSum(int gid, int sum);
        public void onDownload(int gid, int downloadSize, int totalSize);
        public void onUpdateSpeed(int gid, int speed);
        public void onDownloadOver(int gid, int legacy);
    }

    ExDownloader(Context context, int gid, String token, String title, int mode) {
        mContext = context;
        mManager = ExDownloaderManager.getInstance();
        mGid = gid;
        mToken = token;
        mTitle = title;
        mMode = mode;

        mWorkerNum = Config.getDownloadThread();
        mWorkerArray = new Worker[mWorkerNum];
        mControlorArray = new HttpHelper.DownloadControlor[mWorkerNum];

        // Make sure dir
        mDir = EhUtils.getGalleryDir(mGid, mTitle);
        Utils.ensureDir(mDir, true);
    }

    public void setListenerForImageSet(ListenerForImageSet l) {
        mLfis = l;
    }

    public void setListenerDownload(ListenerForDownload l) {
        mLfd = l;
    }

    void occupy() {
        mOwnerNum++;
    }

    void free() {
        mOwnerNum--;
    }

    boolean isOrphans() {
        return mOwnerNum == 0;
    }

    void stop() {
        // TODO stop all thread, release all resources
        mStopWork = true;
        // Stop download
        for (HttpHelper.DownloadControlor c : mControlorArray) {
            if (c != null)
                c.stop();
        }
        // Wake all thread
        synchronized(mPageTokenLock) {mPageTokenLock.notifyAll();}
        synchronized(mWorkerGetIndexLock) {mWorkerGetIndexLock.notifyAll();}
        synchronized(mWorkerLock) {mWorkerLock.notifyAll();}
    }

    boolean pause(boolean pause) {
        if (mOwnerNum > 1)
            return false;

        mPauseWork = pause;
        if (mPauseWork) {
            // Stop download
            for (HttpHelper.DownloadControlor c : mControlorArray)
                if (c != null) c.stop();
            // Wake all thread
            synchronized(mPageTokenLock) {mPageTokenLock.notifyAll();}
            synchronized(mWorkerGetIndexLock) {mWorkerGetIndexLock.notifyAll();}
            synchronized(mWorkerLock) {mWorkerLock.notifyAll();}
        } else {
            // Reset download
            for (HttpHelper.DownloadControlor c : mControlorArray)
                if (c != null) c.reset();
            ensureStart();
            ensureWorkers();
        }
        return true;
    }

    public int getGid() {
        return mGid;
    }

    public String getToken() {
        return mToken;
    }

    public String getTitle() {
        return mTitle;
    }

    public void setDownloadMode(boolean downloadMode) {
        synchronized (mDownloadLock) {
            if (downloadMode) {
                setStartIndex(0);
                if (mDownloadIndexSet == null)
                    mDownloadIndexSet = new HashSet<Integer>();
                if (mDownloadMode && mLfd != null)
                    mLfd.onStart(mGid);
            } else {
                mDownloadIndexSet = null;
            }
            mDownloadMode = downloadMode;
        }
    }

    public void setStartIndex(int startIndex) {
        if (mDownloadMode) {
            addTargetIndex(startIndex);
        } else {
            if (mImageNum != -1 && startIndex >= mImageNum)
                mStartIndex = 0;
            else
                mStartIndex = startIndex;

            ensureStart();
            ensureWorkers();
        }
    }

    public void addTargetIndex(int index) {
        if (mImageNum != -1 && index >= mImageNum)
            return;

        mRequestIndexArray.offer(index);
        ensureStart();
        ensureWorkers();
    }

    public void setCurReadIndex(final int index) {
        new BgThread() {
            @Override
            public void run() {
                writeCurReadIndex(index);
            }
        }.start();
    }

    public int getMaxEnsureIndex() {
        if (mImageNum != -1) {
            return mImageNum - 1;
        } else {
            return Math.max(Math.max(Math.max(mStartIndex,
                    Math.max(mCurMaxPreviewPage, mPreviewPageNum - 1) * mPreviewPerPage),
                    mPageTokeArray.maxValidIndex()), 0);
        }
    }

    /**
     * If do not know image num, return -1.
     * You should call getMaxEnsureIndex().
     *
     * @return
     */
    public int getImageNum() {
        return mImageNum;
    }

    public AutoExpandArray<String> getImageFilenameArray() {
        return mImageFilenameArray;
    }

    /**
     * If targer index image is downloading, return true
     *
     * @param index
     * @return
     */
    public boolean isDownloading(int index) {
        return mRequstingIndexSet.contains(index);
    }

    private synchronized void ensureStart() {
        if (mMainThread != null)
            return;

        mMainThread = new BgThread(this);
        mMainThread.setPriority(Process.THREAD_PRIORITY_BACKGROUND);
        mMainThread.start();
    }

    private File getExDownloadInfoFile() {
        return new File(mDir, EhUtils.EH_DOWNLOAD_FILENAME);
    }

    private boolean checkMode(int samlpe, int target) {
        return samlpe <= EhClient.MODE_EX && target <= EhClient.MODE_EX
                || samlpe == EhClient.MODE_LOFI && target == EhClient.MODE_LOFI;
    }

    /**
     * This function parser the download info file.<br>
     * The file look like this:<br>
     * <code>
     * 00000000<br>
     * 728874<br>
     * 306429c222<br>
     * 1<br>
     * 4<br>
     * 40<br>
     * 128<br>
     * 1 43a64e6e79<br>
     * </code><br>
     * Fist line is gid, a integer.
     * Second line is token, a ten-character string.
     * Third line is mode.
     * Fourth line is preview page num.
     * Fifth line is preview per page.
     *
     * @param ediFile
     * @return
     */
    private synchronized boolean parserEdiFile(File ediFile) {
        if (!ediFile.exists() || !ediFile.isFile() || !ediFile.canRead()
                || !ediFile.canWrite())
            return false;

        InputStream is = null;
        try {
            is = new BufferedInputStream(new FileInputStream(ediFile),
                    IO_BUFFER_SIZE);
            // skip read index
            Utils.readAsciiLine(is);
            if (mGid != Integer.valueOf(Utils.readAsciiLine(is)) ||
                    !mToken.equals(Utils.readAsciiLine(is)))
                return false;

            if (!checkMode(Integer.valueOf(Utils.readAsciiLine(is)), mMode)) {
                // If preview info is not same
                // skip preview page num and preview per page
                Utils.readAsciiLine(is);
                Utils.readAsciiLine(is);
            } else {
                mPreviewPageNum = Integer.valueOf(Utils.readAsciiLine(is));
                mPreviewPerPage = Integer.valueOf(Utils.readAsciiLine(is));
            }

            mImageNum = Integer.parseInt(Utils.readAsciiLine(is));
            if (mImageNum != -1) {
                mPageTokeArray.setCapacity(mImageNum);
                mImageFilenameArray.setCapacity(mImageNum);
            }
            else if (mPreviewPageNum != -1 && mPreviewPerPage != -1) {
                mPageTokeArray.setCapacity(mPreviewPageNum * mPreviewPerPage);
                mImageFilenameArray.setCapacity(mPreviewPageNum * mPreviewPerPage);
            }

            // read page token info
            try {
                while(true) {
                    String line = Utils.readAsciiLine(is);
                    int pos = line.indexOf(" ");
                    if (pos == -1 && line.length() - pos != 11)
                        continue;
                    try {
                        int index = Integer.parseInt(line.substring(0, pos));
                        String token = line.substring(pos + 1);
                        mPageTokeArray.set(index, token);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                }
            } catch (EOFException e) {
                // Empty
            }
            return true;
        } catch (Throwable e) {
            // If read error
            return false;
        } finally {
            Utils.closeQuietly(is);
        }
    }

    private synchronized void writeEdiFile(File ediFile) {
        try {
            FileWriter writer = new FileWriter(ediFile);
            writer.write(String.format("%08x", mCurReadIndex));
            writer.write("\n");
            writer.write(String.valueOf(mGid));
            writer.write("\n");
            writer.write(mToken);
            writer.write("\n");
            writer.write(mMode + '0');
            writer.write("\n");
            writer.write(String.valueOf(mPreviewPageNum));
            writer.write("\n");
            writer.write(String.valueOf(mPreviewPerPage));
            writer.write("\n");
            writer.write(String.valueOf(mImageNum));
            writer.write("\n");
            for (int i = 0; i < mPageTokeArray.length(); i++) {
                String pageToken = mPageTokeArray.get(i);
                if (pageToken != null) {
                    writer.write(String.valueOf(i));
                    writer.write(" ");
                    writer.write(pageToken);
                    writer.write("\n");
                }
            }
            writer.flush();
            writer.close();
        } catch (IOException e) {}
    }

    private synchronized void writeCurReadIndex(int index) {
        mCurReadIndex = index;
        try {
            RandomAccessFile raf = new RandomAccessFile(getExDownloadInfoFile(), "rw");
            raf.write(String.format("%08x", mCurReadIndex).getBytes());
            raf.close();
        } catch (Throwable e) {}
    }

    public static int readCurReadIndex(int gid, String title) {
        try {
            RandomAccessFile raf = new RandomAccessFile(new File(EhUtils.getGalleryDir(gid, title),
                    EhUtils.EH_DOWNLOAD_FILENAME), "r");
            int index = Integer.parseInt(raf.readLine(), 16);
            raf.close();
            return index;
        } catch (Throwable e) {
            return 0;
        }
    }

    private void getDetailInfo(int pageIndex, File ediFile, boolean needPreviewInfo) throws Exception {
        HttpHelper hh = new HttpHelper(mContext);
        EdDetailParser edp = new EdDetailParser();
        String url = EhClient.getDetailUrl(mGid, mToken, pageIndex, mMode);
        hh.setPreviewMode("m");
        String body = hh.get(url);
        if (body == null)
            throw new Exception(hh.getEMsg() != null ? hh.getEMsg() : "Http error, body == null");
        if (!edp.parser(body, mMode, needPreviewInfo))
            throw new Exception(edp.emsg != null ? edp.emsg : "Parser error, body is \n" + body);
        if (edp.previewStartIndex != pageIndex * mPreviewPerPage)
            throw new Exception("预测与实际不匹配, 预测: " + edp.previewStartIndex +
                    ", 实际: " + pageIndex * mPreviewPerPage + ", body is \n" + body);

        List<String> pageTokenArray = edp.pageTokenArray;
        if (needPreviewInfo)
            mPreviewPerPage = pageTokenArray.size();
        if (mMode == EhClient.MODE_LOFI) {
            // update mCurMaxPreviewPage
            if (edp.isLastPage) {
                mPreviewPageNum = pageIndex + 1;
                mImageNum = pageIndex * mPreviewPerPage + pageTokenArray.size();
            } else if (mPreviewPageNum == -1) {
                mCurMaxPreviewPage = Math.max(mCurMaxPreviewPage, pageIndex + 1);
            }
        } else {
            if (needPreviewInfo) {
                mPreviewPageNum = edp.previewPageNum;
                mImageNum = edp.imageNum;
                mPageTokeArray.setCapacity(mPreviewPageNum * mPreviewPerPage);
                mImageFilenameArray.setCapacity(mPreviewPageNum * mPreviewPerPage);
            }
        }
        for (int i = 0; i < pageTokenArray.size(); i++)
            mPageTokeArray.set(i + edp.previewStartIndex, pageTokenArray.get(i));

        writeEdiFile(ediFile);
    }

    private void ensureWorkers() {
        if (mPreviewPerPage == -1)
            return;

        synchronized(mWorkerArray) {
            for (int i = 0; i < mWorkerArray.length; i++) {
                if (mWorkerArray[i] == null) {
                    Worker worker = new Worker(i);
                    worker.start();
                    mWorkerArray[i] = worker;
                    mControlorArray[i] = new HttpHelper.DownloadControlor();
                }
            }
        }
    }

    private void updateDownload(int index) {
        synchronized (mDownloadLock) {
            if (mDownloadIndexSet != null && !mDownloadIndexSet.contains(index)) {
                mDownloadIndexSet.add(index);
                if (mDownloadMode && mLfd != null)
                    mLfd.onDownload(mGid, mDownloadIndexSet.size(), getMaxEnsureIndex() + 1);
            }
        }
    }

    private static final int BE_NOTIFY_ID = -3;

    private static final void show509Notification(Context context) {
        NotificationManager notifyManager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context);
        mBuilder.setSmallIcon(R.drawable.ic_stat_eh).setContentTitle(context.getString(R.string.notification_be_title))
                .setContentText(context.getString(R.string.notification_be_mesg))
                .setOngoing(false).setAutoCancel(true);
        notifyManager.notify(BE_NOTIFY_ID, mBuilder.build());
    }

    // TODO for download , when to claim download completed
    @Override
    public void run() {
        // Try to get info from file
        try {
            File ediFile = getExDownloadInfoFile();
            if (!parserEdiFile(ediFile) || mPreviewPerPage == -1 ||
                    ((mMode == EhClient.MODE_EX || mMode == EhClient.MODE_G) && mImageNum == -1)) {
                getDetailInfo(0, ediFile, true);
            }

            if (mLfd != null)
                mLfd.onGetSum(mGid, mImageNum);

            ensureWorkers();

            // A loop to get page token
            while (true) {
                // Check stop
                if (mStopWork)
                    break;

                int noTokenIndex = -1;
                int pageIndex = -1;
                synchronized(mPageTokenLock) {
                    if (mNoTokenIndexArray.isEmpty()) {
                        if (mRequestPageIndexArray.isEmpty()) {
                            try {
                                mPageTokenLock.wait();
                            } catch (InterruptedException e) {}
                            continue;
                        } else {
                            pageIndex = mRequestPageIndexArray.poll();
                            mCurRequestPageIndex = pageIndex;
                        }
                    } else {
                        noTokenIndex = mNoTokenIndexArray.poll();
                    }
                }

                if (noTokenIndex != -1) {
                    if (mPageTokeArray.get(noTokenIndex) == null) {
                        getDetailInfo(noTokenIndex / mPreviewPerPage, ediFile, false);
                    } else {
                        // The token is already got, no need to re
                    }
                } else if (noTokenIndex == -1){
                    getDetailInfo(pageIndex, ediFile, false);
                    mCurRequestPageIndex = -1;
                }

                ensureWorkers();

                synchronized(mWorkerGetIndexLock) {mWorkerGetIndexLock.notifyAll();}
                synchronized(mWorkerLock) {mWorkerLock.notifyAll();}
            }
        } catch (Exception e) {

            Log.f(e);

            synchronized (mDownloadLock) {
                if (mDownloadMode && mLfd != null && mDownloadIndexSet != null) {
                    mLfd.onDownloadOver(mGid,
                            getMaxEnsureIndex() + 1 - mDownloadIndexSet.size());
                }
            }

        } finally {
            synchronized (this) {
                mMainThread = null;
            }
        }

        Log.d(TAG, "ExDownloader over");
    }

    private class Worker extends BgThread {

        private final int mIndex;

        public Worker(int index) {
            super();
            mIndex = index;
            setPriority(Process.THREAD_PRIORITY_BACKGROUND);
        }

        private void addNoTokenIndex(int index) {
            synchronized(mPageTokenLock) {
                mNoTokenIndexArray.offer(index);
                mPageTokenLock.notify();
            }
        }

        private void addRequstPageIndex(int pageIndex) {
            synchronized(mPageTokenLock) {
                if (mCurRequestPageIndex != pageIndex &&
                        !mRequestPageIndexArray.contains(pageIndex)) {
                    mRequestPageIndexArray.offer(pageIndex);
                    mPageTokenLock.notify();
                }
            }
        }

        private int getTargetIndex() {
            synchronized(mWorkerGetIndexLock) {
                while (true) {

                    // Check stop
                    if (mStopWork || mPauseWork)
                        return -1;

                    if (!mRequestIndexArray.isEmpty()) {
                        return mRequestIndexArray.poll();
                    } else if (mImageNum != -1 && mStartIndex == mImageNum) {
                        return -1;
                    } else if (mImageNum == -1 && mStartIndex > getMaxEnsureIndex()) {
                        // If do not sure the index is valid

                        Log.d(TAG, "mPreviewPerPage = " + mPreviewPerPage);

                        addRequstPageIndex(getMaxEnsureIndex() / mPreviewPerPage);
                        try {
                            mWorkerGetIndexLock.wait();
                        } catch (InterruptedException e) {}
                    } else {
                        return mStartIndex++;
                    }
                }
            }
        }

        // TODO what if download failed ? what to do ?
        @Override
        public void run() {
            HttpDownloadListener listener = new HttpDownloadListener();
            HttpHelper hh = new HttpHelper(mContext);
            ImagePageParser ipp = new ImagePageParser();
            HttpHelper.DownloadOption option = new HttpHelper.DownloadOption();

            option.setAllowFixingExtension(true);

            while (true) {
                // Check stop
                if (mStopWork || mPauseWork)
                    break;

                int targetIndex = getTargetIndex();
                if (targetIndex == -1)
                    break;

                // Check is the index is requsted
                if (mRequstingIndexSet.contains(targetIndex))
                    continue;

                // Check is this already downloaded
                boolean isAlreadyDownloaded = false;
                // First check mImageFilenameArray
                String imageFilename = mImageFilenameArray.get(targetIndex);
                if (imageFilename != null) {
                    File file = new File(mDir, imageFilename);
                    if (file.exists())
                        isAlreadyDownloaded = true;
                } else {
                    // Can not find image file name, just guess
                    for (String possibleFilename : EhUtils.getPossibleImageFilenames(targetIndex)) {
                        File file = new File(mDir, possibleFilename);
                        if (file.exists()) {
                            isAlreadyDownloaded = true;
                            mImageFilenameArray.set(targetIndex, possibleFilename);
                            break;
                        }
                    }
                }

                if (isAlreadyDownloaded) {
                    Log.d(TAG, "isAlreadyDownloaded");
                    updateDownload(targetIndex);
                    continue;
                }

                mRequstingIndexSet.add(targetIndex);

                listener.index = targetIndex;

                String pageToken = null;
                while (true) {
                    // Check stop
                    if (mStopWork || mPauseWork)
                        break;

                    pageToken = mPageTokeArray.get(targetIndex);
                    if (pageToken == null) {
                        addNoTokenIndex(targetIndex);
                        synchronized(mWorkerLock) {
                            try {mWorkerLock.wait();}
                            catch (InterruptedException e) {}
                        }
                    } else {
                        // Get valid page token, jump out of loop
                        break;
                    }
                }

                // Parser image page
                for (int i = 0; i < 2 && !mStopWork && !mPauseWork; i++) {
                    hh.reset();
                    ipp.reset();
                    String body = hh.get(EhClient.getPageUrl(mGid, pageToken, targetIndex + 1, mMode)
                            + ((i == 1) ? "?nl=48" : ""));
                    if (ipp.parser(body, mMode)) {
                        // Download image
                        HttpHelper.DownloadControlor c = mControlorArray[mIndex];

                        if (Config.getDownloadOriginImage() && ipp.originalImageUrl != null) {

                            Log.d(TAG, "Download origin image");

                            // Download orgin image
                            String imageUrl = ipp.originalImageUrl;
                            // Guess filename
                            String filename = EhUtils.getImageFilename(targetIndex, "jpg");
                            // Download
                            hh.reset();
                            option.setUseProxy(false);
                            if (hh.downloadOriginEhImage(imageUrl, mDir, filename, option, c, listener) == null) {
                                if (hh.getException() instanceof HttpHelper.BandwidthExceededException) {
                                    // Get 509
                                    show509Notification(mContext);
                                    break;
                                } else {
                                    String newImageUrl = hh.getLastUrl();
                                    if (newImageUrl != null) {
                                        // Try download from proxy
                                        hh.reset();
                                        option.setUseProxy(true);
                                        if (hh.downloadEhImage(newImageUrl, mDir, filename, option, c, listener) == null) {
                                            // Download failed
                                            Log.d(TAG, "Download failed");
                                        } else {
                                            // Download successful
                                            Log.d(TAG, "Download successful");
                                        }
                                    } else {
                                        // Download failed
                                        Log.d(TAG, "Download failed");
                                    }
                                }
                            } else {
                                // Download successful
                                Log.d(TAG, "Download successful");
                            }

                            // No need to refresh image url
                            break;
                        } else {
                            // Download scale image
                            String imageUrl = ipp.imageUrl;

                            //Check 509 gif
                            boolean get509 = false;
                            for (String suffix : URL_509_SUFFIX_ARRAY) {
                                if (imageUrl.endsWith(suffix)) {
                                    // Get 509 gif here
                                    get509 = true;
                                    break;
                                }
                            }
                            if (get509) {
                                show509Notification(mContext);
                                break;
                            }

                            // Guess filename
                            String filename = EhUtils.getImageFilename(targetIndex, Utils.getExtension(imageUrl, "jpg"));
                            // Don't put into mImageFilenameArray, because extension maybe be wrong
                            // mImageFilenameArray.set(targetIndex, filename);
                            hh.reset();
                            option.setUseProxy(false);
                            if (hh.downloadEhImage(imageUrl, mDir, filename, option, c, listener) == null) {
                                hh.reset();
                                option.setUseProxy(true);
                                if (hh.downloadEhImage(imageUrl, mDir, filename, option, c, listener) == null) {
                                    if (i == 1) {
                                        // TODO download error
                                        Log.d(TAG, "download error");
                                    }
                                } else {
                                    // download ok
                                    Log.d(TAG, "download ok proxy");
                                    break;
                                }
                            } else {
                                // download ok
                                Log.d(TAG, "download ok");
                                break;
                            }
                        }
                    } else {
                        // TODO parser error, Do somthing
                        Log.d(TAG, "parser error");
                        break;
                    }
                }

                // Remove from requsting set
                mRequstingIndexSet.remove(targetIndex);
            }

            // Worker over
            synchronized(mWorkerArray) {
                // Remove from mWorkerArray
                mWorkerArray[mIndex] = null;
                mControlorArray[mIndex] = null;

                boolean allNull = true;
                for (Worker w : mWorkerArray)
                    if (w != null) {allNull = false; break;}

                synchronized (mDownloadLock) {
                    if (allNull && mDownloadMode && mLfd != null && mDownloadIndexSet != null) {
                        mLfd.onDownloadOver(mGid,
                                getMaxEnsureIndex() + 1 - mDownloadIndexSet.size());
                    }
                }
            }
            Log.d(TAG, "ExDownloader Worker over");
        }
    }

    public class HttpDownloadListener implements HttpHelper.OnDownloadListener {

        public int index;
        private float lastPercent;
        private int lastDownloadSize;

        @Override
        public void onDownloadStartConnect() {
            lastPercent = 0.0f;
            lastDownloadSize = 0;

            if (mLfis != null)
                mLfis.onDownloadStart(index);
        }

        @Override
        public void onDownloadStartDownload(int totalSize) {
            lastPercent = 0.0f;
            lastDownloadSize = 0;
        }

        @Override
        public void onDownloadStatusUpdate(int downloadSize, int totalSize) {
            if (totalSize == -1)
                return;

            float percent = (float)downloadSize / totalSize;
            if (percent - lastPercent >= 0.05f) {
                lastPercent = percent;

                if (mLfis != null)
                    mLfis.onDownloading(index, percent);
            }

            // Update download size
            int accumulateSize = downloadSize - lastDownloadSize;
            lastDownloadSize = downloadSize;
            mAccumulateSize += accumulateSize;
            long curTime = System.currentTimeMillis();
            long interval = curTime - mLastUpdateSpeedTime;
            if (interval > UPDATE_SPEED_INTERVAL) {
                if (mDownloadMode && mLfd != null)
                    mLfd.onUpdateSpeed(mGid, (int)(mAccumulateSize * 1000 / interval));
                mLastUpdateSpeedTime = curTime;
                mAccumulateSize = 0;
            }
        }

        @Override
        public void onDownloadOver(int status, String eMsg) {
            if (status == HttpHelper.DOWNLOAD_OK_CODE) {
                mRequstingIndexSet.remove(index);

                updateDownload(index);
            }

            if (mLfis != null && status == HttpHelper.DOWNLOAD_OK_CODE)
                mLfis.onDownloadComplete(index);
            else if (mLfis != null && status == HttpHelper.DOWNLOAD_FAIL_CODE)
                mLfis.onDownloadFail(index);

            lastPercent = 0.0f;
            lastDownloadSize = 0;
        }

        @Override
        public void onUpdateFilename(String newFilename) {

        }
    }
}
