/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.messaging.datamodel;

import android.content.Context;
import android.net.Uri;

import com.android.messaging.Factory;
import com.android.messaging.util.LogUtil;
import com.google.common.annotations.VisibleForTesting;

import java.io.File;

/**
 * A very simple content provider that can serve mms files from our cache directory.
 */
public class MmsFileProvider extends FileProvider {
    private static final String TAG = LogUtil.BUGLE_TAG;
    // Bkav TrungTH - Sua com => bkav de khong trung voi csdl goc (conflic khong cai ngoai duoc)
    @VisibleForTesting
    static final String AUTHORITY = "bkav.android.messaging.datamodel.MmsFileProvider";
    private static final String RAW_MMS_DIR = "rawmms";

    /**
     * Returns a uri that can be used to access a raw mms file.
     *
     * @return the URI for an raw mms file
     */
    public static Uri buildRawMmsUri() {
        final Uri uri = FileProvider.buildFileUri(AUTHORITY, null);
        final File file = getFile(uri.getPath());
        if (!ensureFileExists(file)) {
            LogUtil.e(TAG, "Failed to create temp file " + file.getAbsolutePath());
        }
        return uri;
    }

    @Override
    File getFile(final String path, final String extension) {
        return getFile(path);
    }

    public static File getFile(final Uri uri) {
        return getFile(uri.getPath());
    }

    private static File getFile(final String path) {
        final Context context = Factory.get().getApplicationContext();
        return new File(getDirectory(context), path + ".dat");
    }

    private static File getDirectory(final Context context) {
        return new File(context.getCacheDir(), RAW_MMS_DIR);
    }
}
