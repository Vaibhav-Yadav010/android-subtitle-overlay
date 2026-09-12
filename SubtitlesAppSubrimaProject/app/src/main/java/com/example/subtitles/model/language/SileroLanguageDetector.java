package com.example.subtitles.model.language;

import android.content.Context;
import android.util.Log;

import com.example.subtitles.util.AssetUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.Result;

/**
 * Language Identification (LID) engine based on Silero ONNX model.
 *
 * <p>
 * Responsibilities:
 * <ul>
 *     <li>Load Silero language classifier model</li>
 *     <li>Maintain sliding audio window using circular buffer</li>
 *     <li>Run ONNX inference periodically</li>
 *     <li>Stabilize predictions using candidate confirmation logic</li>
 * </ul>
 *
 * <p>
 * Detection strategy:
 * <ul>
 *     <li>10-second window</li>
 *     <li>Stride of 4 seconds</li>
 *     <li>Language switches only after 2 consecutive identical detections</li>
 *     <li>Low-confidence predictions are ignored</li>
 * </ul>
 *
 * Thread-safe:
 * - Audio buffer operations guarded by {@code bufferLock}
 * - Language state guarded by {@code langLock}
 */
public class SileroLanguageDetector implements AutoCloseable {
    private static final String TAG = "SileroLangDetector";
    public static final String DICT_NAME = "lang_dict_95.json";
    private final OrtEnvironment env;
    private final OrtSession session;
    private final Object langLock = new Object();
    private static final int WINDOW_MS = 10000;
    private static final int STRIDE_MS = 4000;
    private final int windowSamples;
    private final int strideSamples;
    private final float[] circularBuffer;
    private int writePos = 0;
    private long samplesSinceLastDetect = 0;
    private final Object bufferLock = new Object();
    private String lastCandidateLang = null;
    private int candidateCount = 0;
    private static final float SILENCE_THRESHOLD = 1e-3f;
    private final Map<Integer,String> indexToCode;
    private final Map<String,String> codeToName;

    public SileroLanguageDetector(Context ctx, int sampleRate)
            throws IOException, OrtException, JSONException {
        Log.i(TAG, "SileroLanguageDetector ctor: start");
        this.windowSamples = (WINDOW_MS * sampleRate) / 1000;
        this.strideSamples = (STRIDE_MS * sampleRate) / 1000;
        this.circularBuffer = new float[windowSamples];

        env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
            File modelFile = AssetUtils.getRequiredRuntimeModelFile(ctx, AssetUtils.SILERO_MODEL_FILE);
            session = env.createSession(modelFile.getAbsolutePath(), opts);
        }

        JSONObject dict = AssetUtils.loadJsonObject(ctx, DICT_NAME);
        if (dict == null) {
            Log.e(TAG, "DICT JSON is null; language mapping will be empty");
        } else {
            Log.i(TAG, "Loaded DICT JSON with " + dict.length() + " entries");
        }

        indexToCode = new HashMap<>();
        codeToName = new HashMap<>();

        if (dict != null) {
            Iterator<String> keys = dict.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String[] parts = dict.getString(key).split("\\s*,\\s*", 2);
                String code = parts[0];
                String name = parts.length > 1 ? parts[1] : code;
                int idx = Integer.parseInt(key);

                indexToCode.put(idx, code);
                codeToName.put(code, name);
            }
        }
        Log.i(TAG, "indexToCode size=" + indexToCode.size()
                + "  codeToName size=" + codeToName.size());
    }

    public String acceptChunk(float[] pcmFloats, String currentLang) {
        synchronized (bufferLock) {
            for (float sample : pcmFloats) {
                circularBuffer[writePos++] = sample;
                if (writePos >= windowSamples) writePos = 0;
            }
            samplesSinceLastDetect += pcmFloats.length;
            if (samplesSinceLastDetect >= strideSamples) {
                samplesSinceLastDetect = 0;

                float[] window = new float[windowSamples];
                int tailLen = windowSamples - writePos;
                System.arraycopy(circularBuffer, writePos, window, 0, tailLen);
                System.arraycopy(circularBuffer, 0, window, tailLen, writePos);

                currentLang = runDetectOnce(window, currentLang);
            }
            return currentLang;
        }
    }

    private float softmaxConfidence(float[] logits, int argmax) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : logits) if (v > max) max = v;

        double sum = 0;
        for (float v : logits) sum += Math.exp(v - max);

        return (float) (Math.exp(logits[argmax] - max) / sum);
    }

    private String runDetectOnce(float[] buffer, String currentLang) {
        Log.i(TAG, "runDetectOnce: invoking ONNX on buffer len=" + buffer.length);
        String detected;
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(buffer),
                new long[]{1, buffer.length});
             Result result = session.run(Collections.singletonMap("input", input))) {
            float[][] logits = (float[][]) result.get(0).getValue();
            int index = argmax(logits[0]);
            Log.i(TAG, "runDetectOnce: argmax=" + index);
            detected = indexToCode.get(index);
            Log.i(TAG, "runDetectOnce: mapped index to code=" + detected);
            if (detected == null) {
                Log.e(TAG, "lang is NULL");
                synchronized(langLock) { candidateCount = 0; }
                return currentLang;
            }

            float confidence = softmaxConfidence(logits[0], index);
            if (confidence < 0.4f) {
                Log.d(TAG, "Low confidence: " + confidence + " for " + detected);
                synchronized (langLock) {
                    candidateCount = 0;
                }
                return currentLang;
            }

        } catch (Exception e) {
            Log.w(TAG, "Detection failed, keeping " + currentLang, e);
            synchronized (langLock) {
                candidateCount = 0;
            }
            return currentLang;
        }
        synchronized (langLock) {
            if (detected.equals(lastCandidateLang)) {
                candidateCount++;
            } else {
                lastCandidateLang = detected;
                candidateCount = 1;
            }
            if (candidateCount >= 2 && !currentLang.equals(detected)) {
                Log.i(TAG, "Language switched: " + currentLang + " -> " + detected);
                candidateCount = 0;
                lastCandidateLang = null;
                currentLang = detected;
            }
        }
        return currentLang;
    }

    private boolean isSilent() {
        double sumSq = 0;
        for (float v : circularBuffer) sumSq += v * v;
        double rms = Math.sqrt(sumSq / circularBuffer.length);
        return rms < SILENCE_THRESHOLD;
    }

    private int argmax(float[] arr) {
        int idx = 0;
        for (int i = 1; i < arr.length; i++) if (arr[i] > arr[idx]) idx = i;
        return idx;
    }

    public void stop() {
        synchronized (bufferLock) {
            Arrays.fill(circularBuffer, 0f);
            samplesSinceLastDetect = 0;
        }
        synchronized (langLock) {
            candidateCount = 0;
            lastCandidateLang = null;
        }
    }

    public String getCurrentLangName(String currentLang) {
        if(currentLang==null || currentLang.isEmpty() || !codeToName.containsKey(currentLang)) {
            return "";
        }
        synchronized (langLock) {
            return codeToName.get(currentLang);
        }
    }

    @Override
    public void close() {
        synchronized (bufferLock) {
            stop();
            try {
                session.close();
            } catch (OrtException e) {
                Log.w(TAG, "Error closing session", e);
            }
        }
        env.close();
    }
}
