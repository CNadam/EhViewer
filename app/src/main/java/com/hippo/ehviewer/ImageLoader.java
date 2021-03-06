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

package com.hippo.ehviewer;

import android.content.Context;
import android.graphics.Bitmap;

import com.hippo.ehviewer.network.HttpHelper;
import com.hippo.ehviewer.cache.AnyCache;
import com.hippo.ehviewer.cache.ImageCache;
import com.hippo.ehviewer.util.BgThread;

import java.util.Stack;

public class ImageLoader {
    @SuppressWarnings("unused")
    private static final String TAG = ImageLoader.class.getSimpleName();

    public static final int STATE_NONE = 0;
    public static final int STATE_FROM_MEMORY = 1;
    public static final int STATE_FROM_DISK = 2;
    public static final int STATE_FROM_NETWORK = 3;

    private static ImageLoader sInstance;

    class LoadTask {
        public String url;
        public String key;
        public OnGetImageListener listener;
        public Bitmap bitmap;
        public int state;

        public LoadTask(String url, String key, OnGetImageListener listener) {
            this.url = url;
            this.key = key;
            this.listener = listener;
        }
    }

    private final Context mContext;
    private final Stack<LoadTask> mLoadTasks;
    private final AnyCache<Bitmap> mImageCache;
    private final ImageDownloader mImageDownloader;

    private ImageLoader(Context context) {
        mLoadTasks = new Stack<LoadTask>();
        mImageDownloader = new ImageDownloader();

        mContext = context;
        mImageCache = ImageCache.getImageCache(mContext);

        new BgThread(new LoadFromCacheTask()).start();
    }

    public final static ImageLoader getInstance(final Context context) {
        if (sInstance == null) {
            sInstance = new ImageLoader(context.getApplicationContext());
        }
        return sInstance;
    }

    public void add(String url, String key, OnGetImageListener listener) {
        synchronized (mLoadTasks) {
            mLoadTasks.push(new LoadTask(url, key, listener));
            mLoadTasks.notify();
        }
    }

    private class LoadImageCallback implements Runnable {

        private final LoadTask mLoadTask;

        private LoadImageCallback(LoadTask task) {
            mLoadTask = task;
        }

        @Override
        public void run() {
            mLoadTask.listener.onGetImage(mLoadTask.key, mLoadTask.bitmap, mLoadTask.state);
        }
    }

    private class LoadFromCacheTask implements Runnable {
        @Override
        public void run() {
            LoadTask loadTask;
            while (true) {
                synchronized (mLoadTasks) {
                    if (mLoadTasks.isEmpty()) {
                        try {
                            mLoadTasks.wait();
                        } catch (InterruptedException e) {}
                        continue;
                    }
                    loadTask = mLoadTasks.pop();
                }

                int[] state = new int[1];
                String key = loadTask.key;
                loadTask.bitmap = mImageCache.get(key);
                loadTask.state = state[0];

                if (loadTask.bitmap != null)
                    AppHandler.getInstance().post(new LoadImageCallback(loadTask));
                else
                    mImageDownloader.add(loadTask);
            }
        }
    }

    private class ImageDownloader {
        private static final int MAX_DOWNLOAD_THREADS = 5;
        private final Stack<LoadTask> mDownloadTasks;
        private int mWorkingThreadNum = 0;

        public ImageDownloader() {
            mDownloadTasks = new Stack<LoadTask>();
        }

        public void add(LoadTask loadTask) {
            synchronized (mDownloadTasks) {
                mDownloadTasks.push(loadTask);
                if (mWorkingThreadNum < MAX_DOWNLOAD_THREADS) {
                    new BgThread(new DownloadImageTask()).start();
                    mWorkingThreadNum++;
                }
            }
        }

        private class DownloadImageTask implements Runnable {
            @Override
            public void run() {
                LoadTask loadTask;
                HttpHelper httpHelper = new HttpHelper(mContext);
                while (true) {
                    synchronized (mDownloadTasks) {
                        if (mDownloadTasks.isEmpty()) {
                            loadTask = null;
                            mWorkingThreadNum--;
                            break;
                        }
                        loadTask = mDownloadTasks.pop();
                    }

                    // TODO use proxy to get image
                    loadTask.bitmap = httpHelper.getImage(loadTask.url);
                    if (loadTask.bitmap != null) {
                        mImageCache.put(loadTask.key, loadTask.bitmap);
                        loadTask.state = STATE_FROM_NETWORK;
                    } else {
                        loadTask.state = STATE_NONE;
                    }

                    AppHandler.getInstance().post(new LoadImageCallback(loadTask));
                }
            }
        }
    }

    public interface OnGetImageListener {
        /**
         * bmp is null for fail
         * @param key
         * @param bmp
         */
        void onGetImage(String key, Bitmap bmp, int state);
    }
}
