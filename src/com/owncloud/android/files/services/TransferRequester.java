/**
 *  ownCloud Android client application
 *
 *  @author David A. Velasco
 *
 *  Copyright (C) 2017 ownCloud GmbH.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2,
 *  as published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.files.services;

import android.accounts.Account;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PersistableBundle;

import com.owncloud.android.authentication.AccountUtils;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.datamodel.UploadsStorageManager;
import com.owncloud.android.db.OCUpload;
import com.owncloud.android.db.UploadResult;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.utils.ConnectivityUtils;
import com.owncloud.android.utils.PowerUtils;
import com.owncloud.android.utils.Extras;

/**
 * Facade to start operations in transfer services without the verbosity of Android Intents.
 */
/**
 * Facade class providing methods to ease requesting commands to transfer services {@link FileUploader} and
 * {@link FileDownloader}.
 *
 * Protects client objects from the verbosity of {@link android.content.Intent}s.
 *
 * TODO add methods for {@link FileDownloader}, right now it's just about uploads
 */

public class TransferRequester {

    private static final String TAG = TransferRequester.class.getName();

    /**
     * Call to upload several new files
     */
    public void uploadNewFile(
        Context context,
        Account account,
        String[] localPaths,
        String[] remotePaths,
        String[] mimeTypes,
        Integer behaviour,
        Boolean createRemoteFolder,
        int createdBy
    ) {
        Intent intent = new Intent(context, FileUploader.class);

        intent.putExtra(FileUploader.KEY_ACCOUNT, account);
        intent.putExtra(FileUploader.KEY_LOCAL_FILE, localPaths);
        intent.putExtra(FileUploader.KEY_REMOTE_FILE, remotePaths);
        intent.putExtra(FileUploader.KEY_MIME_TYPE, mimeTypes);
        intent.putExtra(FileUploader.KEY_LOCAL_BEHAVIOUR, behaviour);
        intent.putExtra(FileUploader.KEY_CREATE_REMOTE_FOLDER, createRemoteFolder);
        intent.putExtra(FileUploader.KEY_CREATED_BY, createdBy);

        context.startService(intent);
    }

    /**
     * Call to upload a new single file
     */
    public void uploadNewFile(Context context, Account account, String localPath, String remotePath, int
        behaviour, String mimeType, boolean createRemoteFile, int createdBy) {

        uploadNewFile(
            context,
            account,
            new String[]{localPath},
            new String[]{remotePath},
            new String[]{mimeType},
            behaviour,
            createRemoteFile,
            createdBy
        );
    }

    /**
     * Call to update multiple files already uploaded
     */
    private void uploadUpdate(Context context, Account account, OCFile[] existingFiles, Integer behaviour,
                              Boolean forceOverwrite) {
        Intent intent = new Intent(context, FileUploader.class);

        intent.putExtra(FileUploader.KEY_ACCOUNT, account);
        intent.putExtra(FileUploader.KEY_FILE, existingFiles);
        intent.putExtra(FileUploader.KEY_LOCAL_BEHAVIOUR, behaviour);
        intent.putExtra(FileUploader.KEY_FORCE_OVERWRITE, forceOverwrite);

        context.startService(intent);
    }

    /**
     * Call to update a dingle file already uploaded
     */
    public void uploadUpdate(Context context, Account account, OCFile existingFile, Integer behaviour,
                             Boolean forceOverwrite) {

        uploadUpdate(context, account, new OCFile[]{existingFile}, behaviour, forceOverwrite);
    }


    /**
     * Call to retry upload identified by remotePath
     */
    public void retry (Context context, OCUpload upload) {
        if (upload != null && context != null) {
            Account account = AccountUtils.getOwnCloudAccountByName(
                context,
                upload.getAccountName()
            );
            retry(context, account, upload);

        } else {
            throw new IllegalArgumentException("Null parameter!");
        }
    }


    /**
     * Retry a subset of all the stored failed uploads.
     *
     * @param context           Caller {@link Context}
     * @param account           If not null, only failed uploads to this OC account will be retried; otherwise,
     *                          uploads of all accounts will be retried.
     * @param uploadResult      If not null, only failed uploads with the result specified will be retried;
     *                          otherwise, failed uploads due to any result will be retried.
     */
    public void retryFailedUploads(Context context, Account account, UploadResult uploadResult) {
        UploadsStorageManager uploadsStorageManager = new UploadsStorageManager(context.getContentResolver());
        OCUpload[] failedUploads = uploadsStorageManager.getFailedUploads();
        Account currentAccount = null;
        boolean resultMatch, accountMatch;
        for ( OCUpload failedUpload: failedUploads) {
            accountMatch = (account == null || account.name.equals(failedUpload.getAccountName()));
            resultMatch = (uploadResult == null || uploadResult.equals(failedUpload.getLastResult()));
            if (accountMatch && resultMatch) {
                if (currentAccount == null ||
                    !currentAccount.name.equals(failedUpload.getAccountName())) {
                    currentAccount = failedUpload.getAccount(context);
                }
                retry(context, currentAccount, failedUpload);
            }
        }
    }

    /**
     * Private implementation of retry.
     *
     * @param context           Caller {@link Context}
     * @param account           OC account where the upload will be retried.
     * @param upload            Persisted upload to retry.
     */
    private void retry(Context context, Account account, OCUpload upload) {
        if (upload != null) {
            Intent i = new Intent(context, FileUploader.class);
            i.putExtra(FileUploader.KEY_RETRY, true);
            i.putExtra(FileUploader.KEY_ACCOUNT, account);
            i.putExtra(FileUploader.KEY_RETRY_UPLOAD, upload);
            context.startService(i);
        }
    }

    /**
     * Return 'true' when conditions for a scheduled retry are met.
     *
     * @param context       Caller {@link Context}
     * @return              'true' when conditions for a scheduled retry are met, 'false' otherwise.
     */
    boolean shouldScheduleRetry(Context context) {
        return (
            !ConnectivityUtils.isNetworkActive(context) ||
            PowerUtils.isDeviceIdle(context)
        );
    }

    /**
     * Schedule a future retry of an upload, to be done when a connection via an unmetered network (free Wifi)
     * is available.
     *
     * @param context           Caller {@link Context}.
     * @param jobId             Identifier to set to the retry job.
     * @param remotePath        Full path of the file to upload, relative to root of the OC account.
     * @param accountName       Local name of the OC account where the upload will be retried.
     */
    void scheduleUpload(Context context, int jobId, String accountName, String remotePath) {
        boolean scheduled = scheduleTransfer(
            context,
            RetryUploadJobService.class,
            jobId,
            remotePath,
            accountName
        );

        if (scheduled) {
            Log_OC.d(
                TAG,
                String.format(
                    "Scheduled upload retry for %1s in %2s",
                    remotePath,
                    accountName
                )
            );
        }
    }


    /**
     * Schedule a future retry of a download, to be done when a connection via an unmetered network (free Wifi)
     * is available.
     *
     * @param context           Caller {@link Context}.
     * @param jobId             Identifier to set to the retry job.
     * @param remotePath        Full path of the file to download, relative to root of the OC account.
     * @param accountName       Local name of the OC account where the download will be retried.
     */
    void scheduleDownload(Context context, int jobId, String remotePath, String accountName) {
        boolean scheduled = scheduleTransfer(
            context,
            RetryDownloadJobService.class,
            jobId,
            remotePath,
            accountName
        );

        if (scheduled) {
            Log_OC.d(
                TAG,
                String.format(
                    "Scheduled download retry for %1s in %2s",
                    remotePath,
                    accountName
                )
            );
        }
    }


    /**
     * Schedule a future trasnfer of an upload, to be done when a connection via an unmetered network (free Wifi)
     * is available.
     *
     * @param context                   Caller {@link Context}.
     * @param scheduledRetryService     Class of the appropriate retry service, either to retry downloads
     *                                  or to retry uploads.
     * @param jobId                     Identifier to set to the retry job.
     * @param remotePath                Full path of the file to upload, relative to root of the OC account.
     * @param accountName               Local name of the OC account where the upload will be retried.
     */
    private boolean scheduleTransfer(
        Context context,
        Class<?> scheduledRetryService,
        int jobId,
        String remotePath,
        String accountName
    ) {

        // JobShceduler requires Android >= 5.0 ; do not remove this protection while minSdkVersion is lower
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }

        ComponentName serviceComponent = new ComponentName(
            context,
            scheduledRetryService
        );

        JobInfo.Builder builder = new JobInfo.Builder(jobId, serviceComponent);

        // require unmetered network ("free wifi")
        builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);

        // Persist job and prevent it from being deleted after a device restart
        builder.setPersisted(true);

        // Extra data
        PersistableBundle extras = new PersistableBundle();
        extras.putString(Extras.EXTRA_REMOTE_PATH, remotePath);
        extras.putString(Extras.EXTRA_ACCOUNT_NAME, accountName);
        builder.setExtras(extras);

        JobScheduler jobScheduler =
            (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        jobScheduler.schedule(builder.build());

        return true;

    }

}
