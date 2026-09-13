package com.example.subtitles.model.transcription.core;

import android.content.Context;
import android.util.Log;

import com.example.subtitles.util.AssetUtils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Central manager responsible for loading, downloading, caching,
 * and validating Vosk speech recognition models by language code.
 *
 * Implements a singleton pattern and maintains a local disk cache
 * to avoid repeated downloads.
 */
public class LanguageModelManager {
    private static final String TAG = "LanguageModelManager";
    /** Singleton instance */
    private static volatile LanguageModelManager instance;

    /** Application context */
    private final Context context;

    /** Root directory where models are stored */
    private final File baseDir;

    /** In-memory cache mapping language code → model directory */
    private final Map<String, File> cache = Collections.synchronizedMap(new HashMap<>());

    /** Single-thread executor for model download/extraction */
    private ExecutorService modelExecutor = Executors.newSingleThreadExecutor();

    /** Folder name under filesDir */
    public static final String MODEL_DIR_NAME = "vosk_models";

    /** Set of all language codes with known models */
    private final Set<String> supportedLanguages;

    /**
     * Private constructor for singleton.
     * Initializes model directory, seeds default English model,
     * and builds supported language set.
     */
    private LanguageModelManager(Context ctx) throws IOException {
        context = ctx.getApplicationContext();
        baseDir = new File(context.getFilesDir(), MODEL_DIR_NAME);
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            Log.e(TAG, "Unable to create models directory: " + baseDir.getAbsolutePath());
        }

        try {
            File enDir = AssetUtils.ensureDefaultVoskModel(context);
            if (isValidModelDir(enDir)) {
                cache.put("en", enDir);
                Log.i(TAG, "Default English model loaded into cache");
            } else {
                Log.w(TAG, "Default English model directory was empty or missing!");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize default English model", e);
        }

        ModelAsset asset = ModelAsset.getInstance(context);
        supportedLanguages = asset.getAllSupportedLanguageCodes();
        Log.i(TAG, "Supported languages: " + supportedLanguages);
    }

    /** Returns singleton instance. */
    public static LanguageModelManager getInstance(Context ctx) throws IOException {
        if (instance == null) {
            synchronized (LanguageModelManager.class) {
                if (instance == null) {
                    instance = new LanguageModelManager(ctx);
                }
            }
        }
        return instance;
    }

    /** Returns all language codes that have a model. */
    public Set<String> getSupportedLanguages() {
        return Collections.unmodifiableSet(supportedLanguages);
    }

    /** Checks whether a language is supported. */
    public boolean isLanguageSupported(String langCode) {
        return langCode != null && supportedLanguages.contains(langCode);
    }

    /** If extracted model contains a single nested folder, return the inner folder. */
    private File flattenIfNeeded(File dir) {
        File[] children = dir.listFiles();
        if (children != null && children.length == 1 && children[0].isDirectory()) {
            File single = children[0];
            Log.i(TAG, "flattenIfNeeded: promoting " + single.getName());
            return single;
        }
        return dir;
    }

    /** Validates that directory exists and is non-empty. */
    private boolean isValidModelDir(File dir) {
        return dir != null && dir.exists() && dir.isDirectory()
                && dir.listFiles() != null && dir.listFiles().length > 0;
    }

    /**
     * Loads or downloads the model for the requested language.
     * A failed requested-language load returns null rather than silently
     * returning another language's model.
     */
    public synchronized File loadModel(String langCode) {
        Log.i(TAG, "[loadModel] requested langCode=" + langCode +
                ", cacheKeys=" + cache.keySet());

        if (langCode == null || langCode.isEmpty() || !isLanguageSupported(langCode)) {
            Log.w(TAG, "loadModel: unsupported or invalid language: " + langCode);
            return null;
        }

        File cached = cache.get(langCode);
        if (cached != null && isValidModelDir(cached)) {
            Log.i(TAG, "loadModel: returning cached model for " + langCode);
            return cached;
        }

        try {
            ModelAsset asset = ModelAsset.getInstance(context);
            if (asset.sameModel(langCode)) {
                File same = cache.get(langCode);
                if (same != null && isValidModelDir(same)) {
                    return same;
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "loadModel: problem checking whether requested model is already active", e);
        }

        synchronized (cache) {
            cached = cache.get(langCode);
            if (cached != null && isValidModelDir(cached)) {
                return cached;
            }

            Future<File> future = modelExecutor.submit(() -> {
                Log.i(TAG, "[loadModel→executor] launching copyOrDownload for " + langCode);
                File newModel = ModelAsset.getInstance(context).copyOrDownload(context, langCode);
                if (newModel == null || !newModel.isDirectory()) {
                    throw new IOException("ModelAsset returned null or invalid dir for " + langCode);
                }
                newModel.setLastModified(System.currentTimeMillis());
                return newModel;
            });

            File resultDir = null;
            try {
                resultDir = future.get(300, TimeUnit.SECONDS);
                if (!isValidModelDir(resultDir)) {
                    throw new IOException("Model directory invalid for " + langCode);
                }
                resultDir = flattenIfNeeded(resultDir);
                if (!isValidModelDir(resultDir)) {
                    throw new IOException("Model directory invalid after flattening for " + langCode);
                }
                cache.put(langCode, resultDir);
                Log.i(TAG, "loadModel: loaded new model for " + langCode);
            } catch (TimeoutException te) {
                Log.e(TAG, "loadModel: timeout fetching model for " + langCode, te);
                future.cancel(true);
            } catch (ExecutionException ee) {
                Log.e(TAG, "loadModel: execution error for " + langCode, ee.getCause());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "loadModel: interrupted while fetching model for " + langCode, ie);
            } catch (IOException ioe) {
                Log.e(TAG, "loadModel: IO error for " + langCode, ioe);
            } finally {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }

            // Never substitute another language's model for the requested one.
            if (!isValidModelDir(resultDir)) {
                Log.w(TAG, "loadModel: requested model unavailable; returning null for " + langCode);
                return null;
            }

            return resultDir;
        }
    }

    // ==============================================================
    // ======================= ModelAsset ===========================
    // ==============================================================

    /** Handles reading lang.json and downloading/unzipping models. */
    private static class ModelAsset {
        private static volatile ModelAsset instance;
        private final Map<String, ModelInfo> map;
        private final File baseDir;
        private final String DIR_NAME = MODEL_DIR_NAME;
        private String lastSuccessfulName = "";

        public static ModelAsset getInstance(Context ctx) throws IOException {
            if (instance == null) {
                synchronized (ModelAsset.class) {
                    if (instance == null) {
                        instance = new ModelAsset(ctx);
                    }
                }
            }
            return instance;
        }

        public Set<String> getAllSupportedLanguageCodes() {
            Set<String> supported = new HashSet<>();
            for (String code : map.keySet()) {
                String url = getUrl(code);
                if (url != null && !url.isEmpty()) {
                    supported.add(code);
                }
            }
            return supported;
        }

        private ModelAsset(Context ctx) throws IOException {
            Type type = new TypeToken<Map<String, ModelInfo>>() {}.getType();
            try (InputStreamReader reader = new InputStreamReader(
                    ctx.getAssets().open("lang.json"), "UTF-8")) {
                map = new Gson().fromJson(reader, type);
            } catch (IOException e) {
                Log.e(TAG, "Unable to load lang.json", e);
                throw e;
            }

            baseDir = new File(ctx.getFilesDir(), DIR_NAME);
            if (!baseDir.exists() && !baseDir.mkdirs()) {
                throw new IOException("Unable to create model directory: " + baseDir.getAbsolutePath());
            }
        }

        private String getUrl(String langCode) {
            ModelInfo info = map.get(langCode);
            if (info == null) {
                Log.w(TAG, "No JSON entry for language: " + langCode);
                return null;
            }
            if (info.number == -1) {
                Log.i(TAG, "Language " + langCode + " is explicitly unsupported (code -1)");
                return null;
            }
            if (info.transcript_link == null || info.transcript_link.isEmpty()) {
                Log.i(TAG, "No model available for language: " + langCode);
                return null;
            }
            return info.transcript_link;
        }

        public synchronized File copyOrDownload(Context context, String langCode) throws IOException {
            Log.i(TAG, "[copyOrDownloadInternal] langCode=" + langCode + ", JSON keys=" + map.keySet());

            ModelInfo info = map.get(langCode);
            if (info == null || info.transcript_folder == null || info.transcript_folder.isEmpty()) {
                Log.w(TAG, "Missing transcript_folder for lang: " + langCode);
                return null;
            }
            String folderName = info.transcript_folder;
            File outDir = new File(baseDir, folderName);

            if (outDir.exists() && outDir.isDirectory()) {
                File effectiveDir = outDir;
                File[] children = outDir.listFiles();
                if (children != null && children.length == 1 && children[0].isDirectory()) {
                    effectiveDir = children[0];
                }
                if (isVoskModelComplete(effectiveDir)) {
                    Log.i(TAG, "Valid model already present at " + effectiveDir.getAbsolutePath());
                    lastSuccessfulName = folderName;
                    return effectiveDir;
                }
                Log.w(TAG, "Existing dir for " + langCode + " is incomplete — deleting and re-downloading");
                deleteRecursively(outDir);
            }

            if (!outDir.mkdirs() && !outDir.isDirectory()) {
                throw new IOException("Unable to create model directory: " + outDir.getAbsolutePath());
            }

            String modelUrl = getUrl(langCode);
            if (modelUrl == null || modelUrl.isEmpty()) {
                throw new IOException("No model URL defined for " + langCode);
            }

            final File outerDir = outDir;
            File zipFile = new File(baseDir, "model.zip");
            try {
                if (modelUrl.startsWith("http")) {
                    downloadFile(modelUrl, zipFile);
                } else {
                    try (InputStream in = context.getAssets().open(modelUrl);
                         FileOutputStream out = new FileOutputStream(zipFile)) {
                        byte[] buf = new byte[4096];
                        int read;
                        while ((read = in.read(buf)) != -1) {
                            out.write(buf, 0, read);
                        }
                    }
                }

                unzip(zipFile, outDir);
                File[] ch = outDir.listFiles();
                if (ch != null && ch.length == 1 && ch[0].isDirectory()) {
                    outDir = ch[0];
                }
            } catch (IOException e) {
                Log.w(TAG, "Download/unzip failed for " + langCode + " — cleaning up partial outDir", e);
                deleteRecursively(outerDir);
                throw e;
            } finally {
                if (zipFile.exists() && !zipFile.delete()) {
                    Log.w(TAG, "Failed to delete temporary ZIP: " + zipFile.getAbsolutePath());
                }
            }

            if (!isVoskModelComplete(outDir)) {
                deleteRecursively(outerDir);
                throw new IOException("Incomplete Vosk model after extraction for " + langCode);
            }

            File[] dirs = baseDir.listFiles();
            if (dirs != null) {
                for (File dir : dirs) {
                    if (dir.isDirectory()) {
                        String name = dir.getName();
                        if (!name.equals("en") && !name.equals(folderName)) {
                            deleteRecursively(dir);
                        }
                    }
                }
            }

            lastSuccessfulName = folderName;
            return outDir;
        }

        /**
         * Requires the essential acoustic and configuration files of a Vosk model,
         * not merely the presence of empty am/ and conf/ directories.
         */
        private boolean isVoskModelComplete(File dir) {
            return dir != null && dir.isDirectory()
                    && new File(dir, "am").isDirectory()
                    && new File(dir, "am/final.mdl").isFile()
                    && new File(dir, "conf").isDirectory()
                    && new File(dir, "conf/model.conf").isFile();
        }

        /** Checks if requested language uses the same model folder as the last loaded model. */
        public boolean sameModel(String langCode) {
            if (lastSuccessfulName == null || lastSuccessfulName.isEmpty()) {
                return false;
            }
            try {
                ModelInfo info = map.get(langCode);
                if (info == null || info.transcript_folder == null) {
                    return false;
                }
                File dir = new File(baseDir, info.transcript_folder);
                return lastSuccessfulName.equals(info.transcript_folder)
                        && isVoskModelComplete(dir);
            } catch (Exception e) {
                Log.i(TAG, "problem checking transcript_folder", e);
                return false;
            }
        }

        private void unzip(File zipFile, File targetDir) throws IOException {
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
            } catch (IOException e) {
                Log.e(TAG, "unzip failed: " + zipFile.getAbsolutePath(), e);
                throw e;
            }
        }

        private void deleteRecursively(File fileOrDir) {
            if (fileOrDir == null || !fileOrDir.exists()) {
                return;
            }
            if (fileOrDir.isDirectory()) {
                File[] children = fileOrDir.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursively(child);
                    }
                }
            }
            if (!fileOrDir.delete()) {
                Log.w(TAG, "Failed to delete " + fileOrDir.getAbsolutePath());
            }
        }

        private void downloadFile(String urlStr, File destFile) throws IOException {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.connect();
            try {
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP error code: " + conn.getResponseCode());
                }
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(destFile)) {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                }
            } finally {
                conn.disconnect();
            }
        }
    }
}
