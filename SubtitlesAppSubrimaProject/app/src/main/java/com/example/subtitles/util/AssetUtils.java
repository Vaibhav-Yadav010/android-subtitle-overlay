package com.example.subtitles.util;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * -----------------------------------------------------------------------------
 * AssetUtils
 * -----------------------------------------------------------------------------
 *
 * Central utility class responsible for:
 *
 * • Copying files and folders from Android assets into internal storage
 * • Downloading model files from remote URLs
 * • Unzipping model archives
 * • Loading JSON-based configuration data
 *
 * This class is intentionally stateless and exposes only static methods.
 *
 * All operations are designed to be safe, idempotent, and reusable.
 */
public class AssetUtils {

    /** Log tag. */
    private static final String TAG = "AssetUtils";

    public static final String SILERO_MODEL_FILE = "lang_classifier_95.onnx";
    public static final String PYANNOTE_MODEL_FILE = "model.onnx";
    public static final String WHISPER_TINY_MODEL_FILE = "ggml-tiny.bin";
    public static final String VOSK_MODEL_DIR = "vosk_models";
    public static final String DEFAULT_VOSK_LANGUAGE = "en";

    private static final String SILERO_MODEL_URL =
            "https://huggingface.co/Derur/silero-models/resolve/main/lang95/lang_classifier_95.onnx";
    private static final String PYANNOTE_MODEL_URL =
            "https://huggingface.co/deepghs/pyannote-embedding-onnx/resolve/main/model.onnx";
    private static final String WHISPER_TINY_MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin";
    private static final String DEFAULT_VOSK_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";

    private static final ExecutorService MODEL_DOWNLOAD_EXECUTOR = Executors.newSingleThreadExecutor();

    public interface ModelDownloadCallback {
        void onProgress(String modelName, int percentage);
        void onSuccess();
        void onError(String errorMessage);
    }

    private interface ProgressListener {
        void onProgress(int percentage);
    }

    private static class RuntimeModel {
        final String displayName;
        final String fileName;
        final String url;

        RuntimeModel(String displayName, String fileName, String url) {
            this.displayName = displayName;
            this.fileName = fileName;
            this.url = url;
        }
    }

    private static final RuntimeModel[] REQUIRED_MODELS = new RuntimeModel[]{
            new RuntimeModel("Silero Language Model", SILERO_MODEL_FILE, SILERO_MODEL_URL)
    };

    public static File getRuntimeModelFile(Context context, String fileName) {
        return new File(context.getFilesDir(), fileName);
    }

    public static File getRequiredRuntimeModelFile(Context context, String fileName) throws IOException {
        File modelFile = getRuntimeModelFile(context, fileName);
        if (!modelFile.exists() || modelFile.length() <= 0L) {
            throw new IOException("Model file missing: " + fileName + ". Please download runtime models first.");
        }
        return modelFile;
    }

    public static File getVoskModelsDir(Context context) {
        return new File(context.getFilesDir(), VOSK_MODEL_DIR);
    }

    public static File getDefaultVoskModelDir(Context context) {
        return new File(getVoskModelsDir(context), DEFAULT_VOSK_LANGUAGE);
    }

    public static boolean hasDefaultVoskModel(Context context) {
        return isValidVoskModelDir(getDefaultVoskModelDir(context));
    }

    public static File ensureDefaultVoskModel(Context context) throws IOException {
        return ensureDefaultVoskModel(context, null);
    }

    public static File ensureDefaultVoskModel(Context context, ProgressListener progressListener) throws IOException {
        Context appContext = context.getApplicationContext();
        File voskDir = getVoskModelsDir(appContext);
        if (!voskDir.exists() && !voskDir.mkdirs()) {
            throw new IOException("Unable to create Vosk models directory: " + voskDir.getAbsolutePath());
        }

        File langDir = getDefaultVoskModelDir(appContext);
        if (isValidVoskModelDir(langDir)) {
            return langDir;
        }

        File stagingDir = new File(voskDir, DEFAULT_VOSK_LANGUAGE + "_download");
        File zipFile = new File(voskDir, DEFAULT_VOSK_LANGUAGE + ".zip");

        deleteRecursively(stagingDir);
        deleteRecursively(langDir);

        if (!stagingDir.mkdirs()) {
            throw new IOException("Unable to create staging directory: " + stagingDir.getAbsolutePath());
        }

        try {
            downloadFile(DEFAULT_VOSK_MODEL_URL, zipFile, progressListener);
            unzip(zipFile, stagingDir);

            File preparedDir = locateVoskModelRoot(stagingDir);
            if (preparedDir == null) {
                throw new IOException("Downloaded Vosk archive does not contain a valid Vosk model layout. Contents: "
                        + describeDirectoryContents(stagingDir));
            }

            if (!langDir.mkdirs()) {
                throw new IOException("Unable to create target Vosk model directory: " + langDir.getAbsolutePath());
            }

            copyDirectoryContents(preparedDir, langDir);

            if (!isValidVoskModelDir(langDir)) {
                throw new IOException("Default Vosk English model is incomplete: " + langDir.getAbsolutePath());
            }
        } finally {
            if (zipFile.exists() && !zipFile.delete()) {
                Log.w(TAG, "Failed to delete temporary Vosk ZIP: " + zipFile.getAbsolutePath());
            }
            deleteRecursively(stagingDir);
        }

        if (!isValidVoskModelDir(langDir)) {
            throw new IOException("Default Vosk English model is incomplete: " + langDir.getAbsolutePath());
        }

        return langDir;
    }

    public static void ensureRuntimeModelsDownloaded(Context context, ModelDownloadCallback callback) {
        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        MODEL_DOWNLOAD_EXECUTOR.execute(() -> {
            try {
                File filesDir = appContext.getFilesDir();
                if (!filesDir.exists() && !filesDir.mkdirs()) {
                    throw new IOException("Unable to create internal storage directory.");
                }

                for (RuntimeModel model : REQUIRED_MODELS) {
                    File modelFile = new File(filesDir, model.fileName);
                    if (modelFile.exists() && modelFile.length() > 0L) {
                        postProgress(mainHandler, callback, model.displayName, 100);
                        continue;
                    }

                    postProgress(mainHandler, callback, model.displayName, 0);
                    try {
                        downloadFile(model.url, modelFile, percent ->
                                postProgress(mainHandler, callback, model.displayName, percent));
                    } catch (Exception e) {
                        throw new IOException("Failed while downloading " + model.displayName + ": " + e.getMessage(), e);
                    }
                }

                final String voskDisplayName = "Vosk English Model";
                if (hasDefaultVoskModel(appContext)) {
                    postProgress(mainHandler, callback, voskDisplayName, 100);
                } else {
                    postProgress(mainHandler, callback, voskDisplayName, 0);
                    ensureDefaultVoskModel(appContext, percent ->
                            postProgress(mainHandler, callback, voskDisplayName, percent));
                    postProgress(mainHandler, callback, voskDisplayName, 100);
                }

                mainHandler.post(callback::onSuccess);
            } catch (Exception e) {
                Log.e(TAG, "ensureRuntimeModelsDownloaded failed", e);
                final String message = buildNetworkFriendlyError(e);
                mainHandler.post(() -> callback.onError(message));
            }
        });
    }

    private static void postProgress(Handler handler,
                                     ModelDownloadCallback callback,
                                     String modelName,
                                     int percentage) {
        int safePercent = Math.max(0, Math.min(100, percentage));
        handler.post(() -> callback.onProgress(modelName, safePercent));
    }

    private static String buildNetworkFriendlyError(Exception e) {
        if (e instanceof java.net.UnknownHostException) {
            return "No internet connection. Please connect to the internet and try again.";
        }
        if (e instanceof SocketTimeoutException) {
            return "Download timed out. Please check your connection and try again.";
        }
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return "Failed to download required AI models.";
        }
        return "Failed to download required AI models: " + msg;
    }

    public static File copyFileIfNotExists(Context context, String assetName) throws IOException {
        File targetDir = context.getFilesDir();
        File outFile = new File(targetDir, assetName);
        if (outFile.exists()) {
            Log.i(TAG, "Asset already exists: " + outFile.getAbsolutePath());
            return outFile;
        }

        AssetManager am = context.getAssets();
        try (InputStream in = am.open(assetName);
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            out.flush();
            Log.i(TAG, "Copied asset to: " + outFile.getAbsolutePath());
            return outFile;
        } catch (IOException e) {
            Log.e(TAG, "copyFileIfNotExists failed for " + assetName, e);
            throw e;
        }
    }

    public static void copyAssetFolder(Context ctx, String assetFolder, File destDir) throws IOException {
        AssetManager am = ctx.getAssets();
        String[] entries = am.list(assetFolder);

        if (entries == null || entries.length == 0) {
            try (InputStream in = am.open(assetFolder);
                 FileOutputStream out = new FileOutputStream(destDir)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
            }
        } else {
            destDir.mkdirs();
            for (String e : entries) {
                String childAssetPath = assetFolder + "/" + e;
                File childDest = new File(destDir, e);
                copyAssetFolder(ctx, childAssetPath, childDest);
            }
        }
    }

    public static JSONObject loadJsonObject(Context ctx, String assetName)
            throws IOException, JSONException {
        try (InputStream is = ctx.getAssets().open(assetName)) {
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            String json = new String(buffer, StandardCharsets.UTF_8);
            return new JSONObject(json);
        }
    }

    public static void downloadFile(String urlStr, File destFile) throws IOException {
        downloadFile(urlStr, destFile, null);
    }

    private static void downloadFile(String urlStr, File destFile, ProgressListener progressListener) throws IOException {
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory: " + parent.getAbsolutePath());
        }

        File tempFile = new File(destFile.getAbsolutePath() + ".part");
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "SubtitlesApp/1.0");
        conn.connect();

        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP error code: " + conn.getResponseCode());
        }

        int contentLength = conn.getContentLength();

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(tempFile)) {
            byte[] buf = new byte[4096];
            int len;
            long totalRead = 0L;
            int lastPercent = -1;

            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                totalRead += len;

                if (progressListener != null && contentLength > 0) {
                    int percent = (int) ((100L * totalRead) / contentLength);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        progressListener.onProgress(percent);
                    }
                }
            }

            out.flush();

            if (destFile.exists() && !destFile.delete()) {
                throw new IOException("Failed to replace existing file: " + destFile.getAbsolutePath());
            }
            if (!tempFile.renameTo(destFile)) {
                throw new IOException("Failed to finalize download: " + destFile.getAbsolutePath());
            }

            if (progressListener != null) {
                progressListener.onProgress(100);
            }

            Log.i(TAG, "Downloaded file to: " + destFile.getAbsolutePath());
        } finally {
            conn.disconnect();
            if (tempFile.exists() && !tempFile.equals(destFile)) {
                tempFile.delete();
            }
        }
    }

    /** Extracts ZIP archive into targetDir without allowing entries to escape it. */
    public static void unzip(File zipFile, File targetDir) throws IOException {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Unable to create target directory: " + targetDir.getAbsolutePath());
        }

        final File canonicalTarget = targetDir.getCanonicalFile();
        final String targetPrefix = canonicalTarget.getPath() + File.separator;

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(canonicalTarget, entry.getName()).getCanonicalFile();

                if (!outFile.getPath().startsWith(targetPrefix)) {
                    throw new IOException("Unsafe ZIP entry path: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw new IOException("Unable to create directory: " + outFile.getAbsolutePath());
                    }
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Unable to create directory: " + parent.getAbsolutePath());
                    }

                    try (FileOutputStream out = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[4096];
                        int len;
                        while ((len = zis.read(buf)) != -1) {
                            out.write(buf, 0, len);
                        }
                        out.flush();
                    }
                }
                zis.closeEntry();
            }
            Log.i(TAG, "Unzipped to: " + canonicalTarget.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "unzip failed: " + zipFile.getAbsolutePath(), e);
            throw e;
        }
    }

    private static File locateVoskModelRoot(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        if (isValidVoskModelDir(dir)) {
            return dir;
        }

        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }

        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }

            File nested = locateVoskModelRoot(child);
            if (nested != null) {
                return nested;
            }
        }

        return null;
    }

    private static boolean isValidVoskModelDir(File dir) {
        return isValidVoskModelDirV2(dir) || isValidVoskModelDirV1(dir);
    }

    private static boolean isValidVoskModelDirV2(File dir) {
        return dir != null
                && dir.exists()
                && dir.isDirectory()
                && new File(dir, "am/final.mdl").isFile()
                && new File(dir, "conf/model.conf").isFile()
                && (new File(dir, "conf/mfcc.conf").isFile()
                || new File(dir, "conf/fbank.conf").isFile());
    }

    private static boolean isValidVoskModelDirV1(File dir) {
        return dir != null
                && dir.exists()
                && dir.isDirectory()
                && new File(dir, "final.mdl").isFile()
                && (new File(dir, "mfcc.conf").isFile()
                || new File(dir, "fbank.conf").isFile());
    }

    private static void copyDirectoryContents(File sourceDir, File targetDir) throws IOException {
        File[] children = sourceDir.listFiles();
        if (children == null) {
            throw new IOException("Cannot read directory: " + sourceDir.getAbsolutePath());
        }

        for (File child : children) {
            File target = new File(targetDir, child.getName());
            if (child.isDirectory()) {
                if (!target.exists() && !target.mkdirs()) {
                    throw new IOException("Unable to create directory: " + target.getAbsolutePath());
                }
                copyDirectoryContents(child, target);
            } else {
                copyFile(child, target);
            }
        }
    }

    private static void copyFile(File sourceFile, File targetFile) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create directory: " + parent.getAbsolutePath());
        }

        try (FileInputStream in = new FileInputStream(sourceFile);
             FileOutputStream out = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    private static void deleteRecursively(File target) {
        if (target == null || !target.exists()) {
            return;
        }

        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }

        if (!target.delete()) {
            Log.w(TAG, "Failed to delete path: " + target.getAbsolutePath());
        }
    }

    private static String describeDirectoryContents(File dir) {
        if (dir == null || !dir.exists()) {
            return "<missing>";
        }

        File[] children = dir.listFiles();
        if (children == null || children.length == 0) {
            return "<empty>";
        }

        StringBuilder builder = new StringBuilder();
        for (File child : children) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(child.getName());
            if (child.isDirectory()) {
                builder.append('/');
            }
        }
        return builder.toString();
    }

    public static File copyOrDownload(Context context,
                                      String modelSpecifier,
                                      File outDir) throws IOException {
        if (outDir.exists()
                && outDir.isDirectory()
                && outDir.listFiles().length > 0) {
            Log.i(TAG, "Model dir exists: " + outDir.getAbsolutePath());
            return outDir;
        }

        outDir.mkdirs();

        if (modelSpecifier.startsWith("http")) {
            File zipFile = new File(outDir.getParentFile(), "model.zip");
            try {
                downloadFile(modelSpecifier, zipFile);
                unzip(zipFile, outDir);
            } finally {
                if (zipFile.exists()) zipFile.delete();
            }
        } else {
            AssetManager am = context.getAssets();
            try (InputStream assetStream = am.open(modelSpecifier)) {
                File zipFile = new File(outDir.getParentFile(), "model.zip");
                try (FileOutputStream fos = new FileOutputStream(zipFile)) {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = assetStream.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                    }
                }

                unzip(zipFile, outDir);
                zipFile.delete();
            } catch (IOException e) {
                throw new IOException(
                        "Asset model not found or unzip failed for "
                                + modelSpecifier, e);
            }
        }

        return outDir;
    }

    public static List<String> loadStringList(Context ctx, String assetName)
            throws IOException {
        try (InputStream is = ctx.getAssets().open(assetName);
             InputStreamReader reader = new InputStreamReader(is, "UTF-8")) {
            Type mapType = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> map = new Gson().fromJson(reader, mapType);
            return map.entrySet()
                    .stream()
                    .sorted(Comparator.comparingInt(e -> Integer.parseInt(e.getKey())))
                    .map(Map.Entry::getValue)
                    .collect(Collectors.toList());
        }
    }

    public static Map<String, String> loadLangCodeMap(Context ctx, String filename) throws IOException {
        try (InputStream is = ctx.getAssets().open(filename)) {
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            String json = new String(buffer, StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(json);
            Map<String, String> langMap = new HashMap<>();

            for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                String key = it.next();
                String val = obj.getString(key);
                String[] parts = val.split(",", 2);
                if (parts.length == 2) {
                    langMap.put(parts[0].trim(), parts[1].trim());
                } else {
                    langMap.put(parts[0].trim(), parts[0].trim());
                }
            }
            return langMap;
        } catch (JSONException e) {
            throw new IOException("Invalid JSON in " + filename, e);
        }
    }
}
