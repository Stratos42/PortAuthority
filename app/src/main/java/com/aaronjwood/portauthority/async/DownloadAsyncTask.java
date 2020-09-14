package com.aaronjwood.portauthority.async;

import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;

import com.aaronjwood.portauthority.R;
import com.aaronjwood.portauthority.db.Database;
import com.aaronjwood.portauthority.parser.Parser;
import com.aaronjwood.portauthority.response.MainAsyncResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;

class DownloadProgress {
    public String message;
    public int progress;

    @Override
    public String toString() {
        return message;
    }
}

public abstract class DownloadAsyncTask extends AsyncTask<Void, DownloadProgress, Void> {
    private ProgressDialog dialog;

    protected Database db;
    protected WeakReference<MainAsyncResponse> delegate;

    Parser parser;

    /**
     * Creates and displays the progress dialog.
     */
    @Override
    protected void onPreExecute() {
        MainAsyncResponse activity = delegate.get();
        if (activity == null) {
            return;
        }

        Context ctx = (Context) activity;
        dialog = new ProgressDialog(ctx, R.style.DialogTheme);
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setMessage(ctx.getResources().getString(R.string.downloadingData));
        dialog.setIndeterminate(false);
        dialog.setMax(100);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(dialogInterface -> {
            dialogInterface.cancel();
            cancel(true);
        });
        dialog.show();
    }

    /**
     * Downloads and parses data based on the service URL and parser.
     *
     * @param service
     * @param parser
     */
    final void doInBackground(String service, Parser parser) {
        BufferedReader in = null;
        HttpsURLConnection connection = null;
        db.beginTransaction();
        DownloadProgress downProg = new DownloadProgress();
        try {
            URL url = new URL(service);
            connection = (HttpsURLConnection) url.openConnection();
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.connect();
            if (connection.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                downProg.message = connection.getResponseCode() + " " + connection.getResponseMessage();
                publishProgress(downProg);

                return;
            }

            // Get the content length ourselves since connection.getContentLengthLong() requires a minimum API of 24+.
            // The same is true for connection.getHeaderFieldLong().
            String contentLen = connection.getHeaderField("Content-Length");
            long len = Long.parseLong(contentLen);
            in = new BufferedReader(new InputStreamReader(new GZIPInputStream(connection.getInputStream()), "UTF-8"));
            String line;
            long total = 0;
            while ((line = in.readLine()) != null) {
                if (isCancelled()) {
                    return;
                }

                // Lean on the fact that we're working with UTF-8 here.
                // Also, make a rough estimation of how much we need to reduce this to account for the compressed data we've received.
                total += line.length() / 3;
                downProg.progress = (int) (total * 100 / len);
                publishProgress(downProg);
                String[] data = parser.parseLine(line);
                if (data == null) {
                    continue;
                }

                if (parser.exportLine(db, data) == -1) {
                    downProg.message = "Failed to insert data into the database. Please run this operation again";
                    publishProgress(downProg);
                    return;
                }
            }

            db.setTransactionSuccessful();
        } catch (Exception e) {
            downProg.message = e.toString();
            publishProgress(downProg);
        } finally {
            db.endTransaction();
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException ignored) {
            }

            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Override
    protected void onProgressUpdate(DownloadProgress... progress) {
        DownloadProgress currProg = progress[0];
        MainAsyncResponse activity = delegate.get();
        if (currProg.message != null && activity != null) {
            activity.processFinish(new Exception(currProg.message));
            return;
        }

        dialog.setProgress(currProg.progress);
    }

    /**
     * Dismisses the dialog.
     *
     * @param result
     */
    @Override
    protected void onPostExecute(Void result) {
        MainAsyncResponse activity = delegate.get();
        if (activity != null) {
            dialog.dismiss();
        }
    }

}
